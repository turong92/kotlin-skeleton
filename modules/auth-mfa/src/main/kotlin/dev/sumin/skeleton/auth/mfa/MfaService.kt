package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.Reauth
import dev.sumin.skeleton.account.ReauthInput
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.ChallengeStore
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.crypto.TextEncryptor
import java.net.URLEncoder
import java.time.Instant

/** 두 번째 단계로 내미는 것 - 인증 앱의 6자리 코드 또는 복구 코드 하나 */
sealed interface MfaFactor {
    val method: String

    data class TotpCode(val code: String) : MfaFactor {
        override val method get() = "totp"
        override fun toString() = "TotpCode(<redacted>)"
    }

    data class RecoveryCode(val code: String) : MfaFactor {
        override val method get() = "recovery_code"
        override fun toString() = "RecoveryCode(<redacted>)"
    }
}

data class MfaStatus(val enabled: Boolean, val enabledAt: Instant?, val recoveryCodesRemaining: Int, val required: Boolean)

/** [secret] · [otpauthUri] 는 이 값에만 있다(저장은 봉투뿐) */
class Enrollment(val secret: String, val otpauthUri: String, val qr: QrImage, val expiresAt: Instant) {
    override fun toString() = "Enrollment(expiresAt=$expiresAt, secret=<redacted>)"
}

/** [recoveryCodes] 는 첫 등록에만(한 번뿐) - 재등록은 null(기존 코드가 유지된다) */
class EnrollmentConfirmed(val recoveryCodes: List<String>?) {
    override fun toString() = "EnrollmentConfirmed(recoveryCodes=${if (recoveryCodes == null) "none" else "<redacted>"})"
}

/**
 * 2단계(TOTP)의 규칙 - 등록 · 확인 · 로그인 두 번째 단계 · 복구 코드 · 끄기 · 재발급.
 * **다시 인증은 이 안에서 한다**(`Reauth`, 정책 검사 같은 실패가 증거를 태우지 않게 일이 끝난 뒤 `commit`). 비밀은 `TextEncryptor` 봉투로만 저장소에 가고,
 * 코드를 맞히려는 모든 시도(로그인 · 등록 확인 · 끄기)는 같은 계정 한도를 쓴다: 틀린 것만 센다(먼저 세고 맞으면 되돌린다 - 동시에 몰려도 한도 밖은 통과하지 못한다).
 */
class MfaService(
    private val store: MfaStore,
    private val encryptor: TextEncryptor,
    private val core: AccountCore,
    private val challengeStore: ChallengeStore,
    private val authAccounts: AuthAccountRepository,
    private val reauth: Reauth,
    private val policy: MfaPolicy,
    private val requirement: MfaRequirement,
    private val properties: MfaProperties,
    private val issuer: String,
    private val limits: () -> RateLimitStore,
) {
    fun status(accountId: String): MfaStatus {
        val account = account(accountId)
        val record = store.find(accountId)?.takeIf { it.enabled }
        return MfaStatus(record != null, record?.enabledAt, if (record != null) store.recoveryCodesRemaining(accountId) else 0, policy.required(account))
    }

    // ---------------------------------------------------------------- 등록

    /** 새 비밀을 만들어 대기에 둔다(이미 켜져 있으면 재등록 - 옛 비밀은 새 것이 확인될 때까지 산다) */
    fun startEnrollment(accountId: String, sessionId: String?, proof: ReauthInput): Enrollment {
        val account = account(accountId)
        val checked = if (enrollmentDoorOpensFor(account, sessionId)) Reauth.Proof.NONE else reauth.check(account, proof, sessionId)
        val now = core.time.now()
        val secret = Base32.encode(Totp.newSecret())
        val expiresAt = now.plus(properties.enrollmentTtl)
        store.startEnrollment(accountId, encryptor.encrypt(secret), expiresAt, now)
        checked.commit()
        val uri = otpauthUri(secret, account)
        return Enrollment(secret, uri, QrSvg.of(uri), expiresAt)
    }

    /** 첫 코드로 켠다 - 첫 등록이면 복구 코드 10개(한 번뿐), 켤 때 **이 세션 말고** 모든 세션을 닫는다 */
    fun confirmEnrollment(accountId: String, sessionId: String?, code: String, ip: String?): EnrollmentConfirmed {
        val account = account(accountId)
        val attempt = begin(accountId)
        val now = core.time.now()
        val record = store.find(accountId)
        val pending = record?.pendingSecretEnc?.takeIf { record.pendingExpiresAt?.isAfter(now) == true }
        if (pending == null) {
            attempt.forgive()   // 맞히려는 시도가 아니다
            throw MfaException(MfaErrorCode.ENROLLMENT_EXPIRED)
        }
        val step = Totp.match(Base32.decode(encryptor.decrypt(pending)), code, now) ?: throw wrong(attempt.left)
        val first = !record.enabled
        val codes = if (first) RecoveryCodes.generate() else null
        if (!store.activate(accountId, pending, step, now, codes?.map(RecoveryCodes::hash))) {
            attempt.forgive()
            throw MfaException(MfaErrorCode.ENROLLMENT_EXPIRED)   // 동시에 온 다른 확인이 이겼다
        }
        attempt.forgive()
        requirement.invalidate(accountId)
        core.sessions()?.revokeAll(accountId, sessionId)
        core.events.publish(AccountEventType.MFA_ENABLED, accountId, ip, mapOf("via" to if (first) "enrollment" else "re_enrollment"))
        notify(account, "enabled")
        return EnrollmentConfirmed(codes)
    }

    // ---------------------------------------------------------------- 로그인 두 번째 단계

    /**
     * `mfaToken` 과 코드로 두 번째 단계를 끝낸다 - 성공하면 토큰을 낼 계정을 돌려준다(대기는 여기서 지워진다 - 한 번뿐).
     * 한도: 계정(429, 대기의 시도를 깎기 전에) → 이 대기의 시도(5번, 다 쓰면 410) → 코드. 틀린 코드는 `data.attemptsLeft` = 이 대기의 남은 시도.
     */
    fun verifyLogin(mfaToken: String, factor: MfaFactor, ip: String?): AuthAccount {
        val now = core.time.now()
        val id = core.challenges.idOf(mfaToken) ?: throw expired()
        val row = core.challenges.find(id)?.takeIf { it.purpose == ChallengePurposes.MFA_LOGIN && it.expiresAt.isAfter(now) && it.attemptsLeft > 0 } ?: throw expired()
        val accountId = row.accountId ?: throw expired()
        val attempt = begin(accountId)
        val spent = challengeStore.spendAttempt(id, now)
        if (spent == null) {
            attempt.forgive()
            throw expired()
        }
        if (!spend(accountId, factor)) {
            core.events.publish(AccountEventType.MFA_FAILED, accountId, ip, mapOf("method" to factor.method))
            throw wrong(spent.attemptsLeft)
        }
        attempt.forgive()
        if (!core.challenges.consume(id)) throw expired()
        val auth = authAccounts.findBy(AccountIdentifier(accountId = accountId)) ?: throw expired()
        core.events.publish(AccountEventType.MFA_VERIFIED, accountId, ip, mapOf("method" to factor.method))
        if (factor is MfaFactor.RecoveryCode) {
            core.events.publish(AccountEventType.MFA_RECOVERY_CODE_USED, accountId, ip, mapOf("remaining" to store.recoveryCodesRemaining(accountId).toString()))
            account(accountId).let { notify(it, "recovery_code_used") }
        }
        return auth
    }

    // ---------------------------------------------------------------- 끄기 · 재발급

    /** 다시 인증 + 지금 코드(인증 앱 또는 복구 코드). 앱이 필수로 정한 계정은 끌 수 없다 */
    fun disable(accountId: String, sessionId: String?, proof: ReauthInput, factor: MfaFactor, ip: String?) {
        val account = account(accountId)
        requireEnabled(accountId)
        if (policy.required(account)) throw MfaException(MfaErrorCode.REQUIRED_BY_POLICY)
        val checked = reauth.check(account, proof, sessionId)
        val attempt = begin(accountId)
        if (!spend(accountId, factor)) throw wrong(attempt.left)
        attempt.forgive()
        store.deleteAll(accountId)
        requirement.invalidate(accountId)
        checked.commit()
        core.events.publish(AccountEventType.MFA_DISABLED, accountId, ip)
        notify(account, "disabled")
    }

    /** 다시 인증 → 새 복구 코드 10개(옛 코드는 모두 무효). 코드는 이 값에만 있다 */
    fun regenerateRecoveryCodes(accountId: String, sessionId: String?, proof: ReauthInput, ip: String?): List<String> {
        val account = account(accountId)
        requireEnabled(accountId)
        val checked = reauth.check(account, proof, sessionId)
        val codes = RecoveryCodes.generate()
        store.replaceRecoveryCodes(accountId, codes.map(RecoveryCodes::hash), core.time.now())
        checked.commit()
        core.events.publish(AccountEventType.MFA_RECOVERY_CODES_REGENERATED, accountId, ip)
        return codes
    }

    /** 탈퇴 - 비밀 · 복구 코드 · 열려 있는 로그인 대기를 지운다(멱등) */
    fun erase(accountId: String) {
        store.deleteAll(accountId)
        requirement.invalidate(accountId)
        challengeStore.deleteByAccount(accountId, listOf(ChallengePurposes.MFA_LOGIN))
    }

    // ---------------------------------------------------------------- 운영자 해제

    /** 켜져 있는 [accountId] 의 2단계를 지우고 모든 세션을 닫는다. [actorPermissions] 는 호출자의 권한(전권이면 `*`) - 자기 자신 409, 자기에게 없는 권한을 가진 계정 403(상승) */
    fun reset(actorId: String, accountId: String, actorPermissions: Set<String>) {
        if (actorId == accountId) throw MfaException(MfaErrorCode.SELF_RESET)
        try {
            core.requireCanManage(accountId, actorPermissions)
        } catch (e: AccountException) {
            throw if (e.errorCode == AccountErrorCode.ROLE_ESCALATION) MfaException(MfaErrorCode.ESCALATION) else e
        }
        val account = account(accountId)
        requireEnabled(accountId)
        store.deleteAll(accountId)
        challengeStore.deleteByAccount(accountId, listOf(ChallengePurposes.MFA_LOGIN))
        requirement.invalidate(accountId)
        core.sessions()?.revokeAll(accountId)
        core.events.publish(AccountEventType.MFA_RESET_BY_ADMIN, accountId, null, mapOf("by" to actorId))
        notify(account, "reset")
    }

    // ---------------------------------------------------------------- 안쪽

    /**
     * 필수 문에 걸린 계정(필수인데 등록 전)이 **방금 로그인한 세션**(`required.fresh-login-window`, 15분)으로 등록하면 다시 인증을 면제한다 - 첫 이메일 등록의 면제와 같은 이유(막혔는데 증거를 대라고 할 수 없다).
     * 면제는 계정이 아니라 세션에 붙는다: 옛 세션 · 세션을 읽을 수 없는 조립(JWT 만)은 면제가 없다. 이미 켜진 계정의 재등록은 면제가 아니다.
     */
    private fun enrollmentDoorOpensFor(account: Account, sessionId: String?): Boolean {
        if (sessionId == null || !policy.required(account) || store.find(account.id)?.enabled == true) return false
        val openedAt = core.sessionInspector()?.openedAt(account.id, sessionId) ?: return false
        return !core.time.now().isAfter(openedAt.plus(properties.required.freshLoginWindow))
    }

    private fun requireEnabled(accountId: String) {
        if (store.find(accountId)?.enabled != true) throw MfaException(MfaErrorCode.NOT_ENABLED)
    }

    /** 요소를 쓴다 - 인증 앱 코드는 그 걸음을 `last_used_step` 으로 한 번만, 복구 코드는 하나만. 틀림 · 이미 씀이면 false */
    private fun spend(accountId: String, factor: MfaFactor): Boolean {
        val now = core.time.now()
        return when (factor) {
            is MfaFactor.TotpCode -> {
                val secretEnc = store.find(accountId)?.secretEnc ?: return false
                val step = Totp.match(Base32.decode(encryptor.decrypt(secretEnc)), factor.code, now) ?: return false
                store.useStep(accountId, step, now)
            }
            is MfaFactor.RecoveryCode -> store.useRecoveryCode(accountId, RecoveryCodes.hash(factor.code), now)
        }
    }

    private fun account(accountId: String): Account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)

    private fun expired() = MfaException(MfaErrorCode.CHALLENGE_EXPIRED)

    private fun wrong(attemptsLeft: Int) = MfaException(MfaErrorCode.CODE_INVALID, mapOf("attemptsLeft" to attemptsLeft))

    /** 계정 한도 하나를 먼저 센 시도 - 맞으면 [forgive] 로 되돌린다. [left] 는 이 시도를 센 뒤 두 창에서 더 틀릴 수 있는 수 */
    private inner class Attempt(val left: Int, private val keys: List<Pair<String, Long>>) {
        fun forgive() = keys.forEach { (key, windowMillis) -> limits().release(key, windowMillis, core.time.now()) }
    }

    /** 걸리면 429 + Retry-After(창마다 메일 한 통 · 이벤트). 걸리지 않으면 이 시도를 센 [Attempt] */
    private fun begin(accountId: String): Attempt {
        val now = core.time.now()
        val short = properties.limits.short
        val daily = properties.limits.daily
        val shortKey = "auth-mfa:wrong:15m:$accountId" to short.window.toMillis()
        val dailyKey = "auth-mfa:wrong:1d:$accountId" to daily.window.toMillis()
        val a = limits().consume(shortKey.first, short.capacity, shortKey.second, now)
        if (!a.allowed) throw blocked(accountId, "short", short, a.resetAt, now)
        val b = limits().consume(dailyKey.first, daily.capacity, dailyKey.second, now)
        if (!b.allowed) {
            limits().release(shortKey.first, shortKey.second, now)
            throw blocked(accountId, "daily", daily, b.resetAt, now)
        }
        return Attempt(minOf(a.remaining, b.remaining), listOf(shortKey, dailyKey))
    }

    private fun blocked(accountId: String, label: String, window: MfaProperties.Window, resetAt: Instant, now: Instant): RateLimitedException {
        // 창마다 한 번 - 같은 창에서 막힌 시도가 메일을 쏟아내지 않는다
        if (limits().consume("auth-mfa:throttle-notice:$label:$accountId", 1, window.window.toMillis(), now).allowed) {
            core.events.publish(AccountEventType.MFA_THROTTLED, accountId, null, mapOf("window" to label))
            notify(account(accountId), "throttled")
        }
        return RateLimitedException((resetAt.epochSecond - now.epochSecond).coerceAtLeast(1), MfaErrorCode.TOO_MANY_ATTEMPTS)
    }

    private fun notify(account: Account, change: String) {
        val email = account.email ?: return
        core.tasks.run("mfa-notice") { core.mailer.send(AccountMail(MailKind.MFA_NOTICE, email, account.locale, vars = mapOf("change" to change))) }
    }

    private fun otpauthUri(secret: String, account: Account): String {
        val label = account.email ?: account.displayName ?: account.id.take(8)
        return "otpauth://totp/${encode(issuer)}:${encode(label)}?secret=$secret&issuer=${encode(issuer)}&algorithm=SHA1&digits=${Totp.DIGITS}&period=${Totp.PERIOD_SECONDS}"
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
