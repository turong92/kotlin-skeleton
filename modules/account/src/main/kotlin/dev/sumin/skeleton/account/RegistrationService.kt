package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

data class SignUpCommand(
    val email: String,
    val password: String,
    val displayName: String?,
    val locale: String?,
    val timeZone: String?,
    val ip: String?,
    val captchaToken: String?,
) {
    override fun toString() = "SignUpCommand(email=<redacted>, password=<redacted>)"
}

enum class SignUpStatus { VERIFICATION_SENT, CREATED }

/**
 * 이메일 + 비밀번호 가입과 이메일 확인.
 *
 * 응답으로 계정 존재 여부가 드러나지 않게, 요청 스레드는 **입력 검사 · 한도 · 캡차 · 비밀번호 해시 한 번 · 계정 저장 한 번**을 새 주소든 있는 주소든
 * 똑같이 하고(유니크 키가 판정한다) 나머지 — 토큰 · 메일 — 는 [AccountCore.tasks] 로 넘긴다. 계정은 큐가 아니라 저장소에 있으므로 202 를 받은 가입이 사라지지 않는다.
 * 이미 있는 주소에는 "이미 계정이 있어요" 메일을 보낸다. 이메일 확인을 끈 앱(`sign-up.email-verification=false`)만 동기로 돌며 중복이 409 로 드러난다.
 */
class RegistrationService(private val core: AccountCore) {
    fun signUp(cmd: SignUpCommand): SignUpStatus {
        val p = core.props
        if (!p.signUp.enabled) throw AccountException(AccountErrorCode.SIGN_UP_CLOSED)
        cmd.ip?.let {
            val a = core.limits.acquire("signup:ip", it, p.signUp.perIp, p.signUp.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        core.captcha.check(cmd.captchaToken, cmd.ip, "sign_up")
        val email = Emails.normalize(cmd.email)
        if (!Emails.plausible(email)) throw ApplicationException("Invalid email", PlatformErrorCode.VALIDATION_FAILED)
        val violations = core.policy.check(cmd.password, email)
        if (violations.isNotEmpty()) throw PasswordPolicyException(violations)
        val hash = core.hasher.hash(cmd.password)

        if (!p.signUp.emailVerification) return createVerified(email, hash, cmd)
        // 계정은 **요청 스레드에서** 저장한다 — 202 를 받은 가입이 재시작 · 큐 넘침으로 행 없이 사라지지 않게. 유니크 키가 판정하므로
        // 새 주소든 있는 주소든 요청 스레드가 하는 일(저장소 호출 한 번)이 같다. 뒤로 가는 것은 메일뿐이고, 잃어도 재전송으로 복구된다
        val account = newAccount(email, cmd, AccountStatus.PENDING_VERIFICATION, verified = false)
        val created = core.accounts.insert(account, listOf(passwordIdentity(account, email, hash, verified = false)))
        core.tasks.run("sign-up") {
            if (created) {
                core.events.publish(AccountEventType.SIGN_UP, account.id, cmd.ip, mapOf("method" to SignInMethods.PASSWORD))
                sendVerification(account)
            } else {
                core.accountByEmail(email)?.let(::notifyAlreadyRegistered)
            }
        }
        return SignUpStatus.VERIFICATION_SENT
    }

    private fun createVerified(email: String, hash: String, cmd: SignUpCommand): SignUpStatus {
        if (core.accountByEmail(email) != null) throw AccountException(AccountErrorCode.EMAIL_TAKEN)
        val account = newAccount(email, cmd, AccountStatus.ACTIVE, verified = false)
        if (!core.accounts.insert(account, listOf(passwordIdentity(account, email, hash, verified = false)))) throw AccountException(AccountErrorCode.EMAIL_TAKEN)
        core.events.publish(AccountEventType.SIGN_UP, account.id, cmd.ip, mapOf("method" to SignInMethods.PASSWORD))
        return SignUpStatus.CREATED
    }

    private fun notifyAlreadyRegistered(existing: Account) {
        val address = existing.email ?: return
        if (existing.status == AccountStatus.DELETED) return
        if (!withinEmailBudget(address)) return
        core.mailer.send(AccountMail(MailKind.ALREADY_REGISTERED, address, existing.locale))
    }

    fun verifyEmail(token: String) {
        val grant = core.tokens.consume(TokenPurposes.VERIFY_EMAIL, token) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val account = grant.accountId?.let(core.accounts::findById)
        // 그 사이 이메일이 바뀌었거나 계정이 사라졌으면 이 링크는 더 이상 그 주소를 증명하지 않는다
        if (account == null || account.email != grant.subject) throw AccountException(AccountErrorCode.TOKEN_INVALID)
        // 링크는 발급 때의 가입 비밀번호에 묶여 있다 — 그 비밀번호가 아니면(다른 길로 바뀌었거나 지워졌으면) 이 클릭이 활성화할 것이 없다
        if (grant.payload != null && grant.payload != core.accounts.findIdentity(SignInMethods.PASSWORD, grant.subject)?.secret?.let(OneTimeTokens::fingerprint)) {
            throw AccountException(AccountErrorCode.TOKEN_INVALID)
        }
        if (!core.accounts.markEmailVerified(account.id, core.time.now())) throw AccountException(AccountErrorCode.TOKEN_INVALID)
        core.events.publish(AccountEventType.EMAIL_VERIFIED, account.id)
        core.accounts.findById(account.id)?.let(core.bootstrap::afterVerified)
    }

    /** 항상 같은 응답 — 주소 조회 · 메일은 뒤에서, 한도를 넘으면 조용히 */
    fun resendVerification(email: String, ip: String?, captchaToken: String?) {
        ip?.let {
            val v = core.props.verification
            val a = core.limits.acquire("resend:ip", it, v.perIp, v.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        core.captcha.check(captchaToken, ip, "resend_verification")
        val normalized = Emails.normalize(email)
        core.tasks.run("resend-verification") {
            val account = core.accountByEmail(normalized)
            if (account != null && account.status == AccountStatus.PENDING_VERIFICATION) sendVerification(account)
        }
    }

    private fun sendVerification(account: Account) {
        val email = account.email ?: return
        if (!withinEmailBudget(email)) return
        val ttl = core.props.verification.ttl
        val bound = core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.secret?.let(OneTimeTokens::fingerprint)
        val raw = core.tokens.issue(TokenPurposes.VERIFY_EMAIL, email, account.id, ttl, payload = bound)
        core.mailer.send(AccountMail(MailKind.VERIFY_EMAIL, email, account.locale, core.links.verify(raw), mapOf("hours" to ttl.toHours().toString())))
    }

    /** 한 주소에 인증 · "이미 계정이 있어요" 메일이 창 안에 몇 통까지 — 남의 주소로 메일 폭탄을 못 보내게. 넘으면 조용히 */
    private fun withinEmailBudget(email: String): Boolean {
        val v = core.props.verification
        return core.limits.acquire("verification:email", email, v.perEmail, v.perEmailWindow).allowed
    }

    private fun newAccount(email: String, cmd: SignUpCommand, status: AccountStatus, verified: Boolean): Account {
        val now = core.time.now()
        return Account(
            id = core.newAccountId(), email = email, emailVerified = verified, status = status, roles = core.props.defaultRoles,
            displayName = ProfileRules.displayName(cmd.displayName), locale = ProfileRules.locale(cmd.locale), timeZone = ProfileRules.timeZone(cmd.timeZone),
            createdAt = now, updatedAt = now,
        )
    }

    private fun passwordIdentity(account: Account, email: String, hash: String, verified: Boolean) =
        Identity(core.newIdentityId(), account.id, SignInMethods.PASSWORD, email, verified, secret = hash, createdAt = account.createdAt)
}
