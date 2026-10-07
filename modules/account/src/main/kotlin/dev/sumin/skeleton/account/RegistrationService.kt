package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.CodeCheck
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import tools.jackson.databind.json.JsonMapper

data class SignUpCommand(
    val email: String,
    val password: String,
    val displayName: String?,
    val locale: String?,
    val timeZone: String?,
    val ip: String?,
    val captchaToken: String?,
    /** 한도 키 — `ClientIps….limitKey`(IPv6 는 /64). [ip] 는 감사 · 캡차용 전체 주소 */
    val ipKey: String? = ip,
    /** 가입자가 보고 동의한 문서 — `legal` 이 있을 때만 쓰인다 (없으면 무시) */
    val consents: List<dev.sumin.skeleton.common.consent.ConsentClaim> = emptyList(),
    /** 동의 기록에 남길 가입 요청의 User-Agent (앞 120 자) */
    val userAgent: String? = null,
) {
    override fun toString() = "SignUpCommand(email=<redacted>, password=<redacted>)"

    companion object {
        /** 한 가입 시도가 실을 수 있는 동의 수 — 시도 행의 payload 열(2000 자)에 들어가는 크기로 묶는다 */
        const val MAX_CONSENTS = 8
        const val MAX_USER_AGENT = 120
    }
}

enum class SignUpStatus { VERIFICATION_SENT, CREATED }

/**
 * [signUpId]: 코드 확인 · 재전송에 쓰는 가입 시도 id — 이메일 확인을 끈 앱(CREATED)에는 없다.
 * [expiresAt] · [resendAvailableAt]: 카운트다운용 ([CodeWindow]) — 시도가 없으면(CREATED) 없다
 */
data class SignUpOutcome(val status: SignUpStatus, val signUpId: String? = null, val expiresAt: java.time.Instant? = null, val resendAvailableAt: java.time.Instant? = null)

/**
 * 이메일 + 비밀번호 가입과 **코드로 하는** 이메일 확인.
 *
 * 가입은 계정이 아니라 **가입 시도**를 만든다: (시도 id · 이메일 · **그 시도에서 입력된** 비밀번호 해시 · 코드 해시 · 만료 · 남은 추측). 같은 주소에 여러 시도가 함께 있을 수 있고(공격자의 것과
 * 주인의 것) 서로 덮어쓰지 않는다. 6자리 코드는 그 주소의 메일함으로만 가고, 시도 id 는 가입을 시작한 브라우저만 안다 — 그래서 남이 시작한 시도는 주인이 끝낼 수 없고(주인은 그 id 를 모른다),
 * 주인 자신의 시도는 주인의 비밀번호로 끝난다. 시도가 확인되기 전에는 계정의 어떤 자격도 저장되지 않는다.
 *
 * 응답으로 주소의 상태가 드러나지 않게, 요청 스레드는 **입력 검사 · 한도 · 캡차 · 비밀번호 해시 한 번 · 시도 저장 한 번**을 새 주소든 있는 주소든 똑같이 하고(계정 저장소는 읽지 않는다)
 * 메일은 [AccountCore.tasks] 로 넘긴다. 이미 계정이 있는 주소에는 코드 대신 "이미 계정이 있어요" 메일이 가고, 그 시도의 코드는 누구도 모른다 — 코드 입력은 새 주소의 틀린 추측과 똑같이 줄어들다 끝난다.
 * 이메일 확인을 끈 앱(`sign-up.email-verification=false`)만 동기로 돌며 중복이 409 로 드러난다.
 */
class RegistrationService(private val core: AccountCore) {
    private val json = JsonMapper.builder().build()

    fun signUp(cmd: SignUpCommand): SignUpOutcome {
        val p = core.props
        if (!p.signUp.enabled) throw AccountException(AccountErrorCode.SIGN_UP_CLOSED)
        cmd.ipKey?.let {
            val a = core.limits.acquire("signup:ip", it, p.signUp.perIp, p.signUp.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        // 동의는 요청 본문과 문서 집합만 본다(주소 · 계정을 보지 않는다) — 캡차보다 먼저, 새 주소든 있는 주소든 똑같이 거절한다
        val gate = core.consents()
        if (cmd.consents.size > SignUpCommand.MAX_CONSENTS) throw ApplicationException("Too many consents", PlatformErrorCode.VALIDATION_FAILED)
        gate?.check(cmd.consents)
        core.captcha.check(cmd.captchaToken, cmd.ip, "sign_up")
        val email = Emails.normalize(cmd.email)
        if (!Emails.plausible(email)) throw ApplicationException("Invalid email", PlatformErrorCode.VALIDATION_FAILED)
        val violations = core.policy.check(cmd.password, email)
        if (violations.isNotEmpty()) throw PasswordPolicyException(violations)
        val hash = core.hasher.hash(cmd.password)

        if (!p.signUp.emailVerification) return SignUpOutcome(createVerified(email, hash, cmd))

        val v = p.verification
        val mayOpen = core.mayOpenCodeFor(email)
        val mayMail = core.mayMailCodeTo(email)
        val profile = json.writeValueAsString(
            buildMap<String, Any?> {
                put("displayName", ProfileRules.displayName(cmd.displayName))
                put("locale", ProfileRules.locale(cmd.locale))
                put("timeZone", ProfileRules.timeZone(cmd.timeZone))
                // 동의는 이 시도에 묶여 시도와 함께 저장된다 — 확인될 때 계정과 같은 트랜잭션에서 기록한다 (고리가 없으면 싣지 않는다)
                if (gate != null) {
                    put("consents", cmd.consents.map { mapOf("type" to it.type, "version" to it.version, "locale" to it.locale) })
                    put("consentIp", cmd.ip)
                    put("consentUa", cmd.userAgent?.take(SignUpCommand.MAX_USER_AGENT))
                }
            },
        )
        // 한도를 넘은 주소의 시도는 저장하지 않는다 — 응답은 같고(그 시도는 어떤 코드로도 끝나지 않는다), 한 주소에 걸린 추측의 총량이 묶인다
        val opened = core.challenges.open(
            ChallengePurposes.SIGN_UP, email, v.codeTtl, v.maxAttempts, payload = profile, secret = hash, ip = cmd.ip, withHandle = true, store = mayOpen,
        )
        core.tasks.run("sign-up") {
            val existing = core.accountByEmail(email)
            if (existing != null && taken(existing)) notifyAlreadyRegistered(existing, mayMail)
            else if (mayMail && mayOpen) sendCode(email, opened.code, cmd.locale)
        }
        // 시간 값은 저장한(또는 한도 때문에 저장하지 않은) 시도 행에서 — 새 주소든 있는 주소든 메일이 나갔든 안 나갔든 같은 계산이다
        return SignUpOutcome(SignUpStatus.VERIFICATION_SENT, opened.handle, opened.row.expiresAt, opened.row.lastSentAt.plus(v.resendCooldown))
    }

    private fun createVerified(email: String, hash: String, cmd: SignUpCommand): SignUpStatus {
        if (core.accountByEmail(email) != null || core.blocks.blocked(email)) throw AccountException(AccountErrorCode.EMAIL_TAKEN)   // 차단도 "이미 있는 주소" 와 같은 응답 — 메일함 증명이 없는 길이다
        val account = newAccount(email, ProfileRules.displayName(cmd.displayName), ProfileRules.locale(cmd.locale), ProfileRules.timeZone(cmd.timeZone), unverified = true)
        core.atomic.run {
            if (!core.accounts.insert(account, listOf(passwordIdentity(account, email, hash, verified = false)))) throw AccountException(AccountErrorCode.EMAIL_TAKEN)
            recordConsents(account.id, cmd.consents, cmd.ip, cmd.userAgent?.take(SignUpCommand.MAX_USER_AGENT))
        }
        core.events.publish(AccountEventType.SIGN_UP, account.id, cmd.ip, mapOf("method" to SignInMethods.PASSWORD))
        return SignUpStatus.CREATED
    }

    /** 확인된(또는 지워지는 중인) 계정이 가진 주소 — 코드로 가져갈 수 없다. 확인되지 않은 계정(이메일 확인을 나중에 켠 앱에 남은 것)은 코드로 메일함을 증명하면 이어받는다 */
    private fun taken(existing: Account) = existing.emailVerified || existing.status == AccountStatus.DELETED

    private fun notifyAlreadyRegistered(existing: Account, allowed: Boolean) {
        val address = existing.email ?: return
        if (existing.status == AccountStatus.DELETED || !allowed) return
        core.mailer.send(AccountMail(MailKind.ALREADY_REGISTERED, address, existing.locale, vars = alreadyRegisteredVars(existing, address)))
    }

    /**
     * "이미 계정이 있어요" 메일의 다음 걸음: 로그인 페이지 · 가입 수단(코드 목록 — 문구는 템플릿이 로케일로) · 비밀번호 재설정 링크(재설정 요청과 같은 상태 규칙 · 주소별 한도 · 토큰) ·
     * (`auth-magic-link` 가 있으면) 1회용 매직 링크. 정지된 계정에는 링크를 주지 않는다(박제). 뒤로 넘긴 일 안에서만 돈다 — 화면 응답은 계정이 있든 없든 같다.
     */
    private fun alreadyRegisteredVars(existing: Account, address: String): Map<String, String> = buildMap {
        core.links.login()?.let { put("loginUrl", it) }
        core.accounts.identitiesOf(existing.id).map { it.method }.distinct().takeIf { it.isNotEmpty() }?.let { put("methods", it.joinToString(",")) }
        if (existing.status == AccountStatus.SUSPENDED) return@buildMap
        core.issueResetLink(existing)?.let {
            put("resetUrl", it.url); put("resetMinutes", it.minutes.toString())
            core.events.publish(AccountEventType.PASSWORD_RESET_REQUESTED, existing.id, detail = mapOf("via" to "already_registered"))
        }
        core.magicLinks()?.issue(existing)?.let { put("magicUrl", it.url); put("magicMinutes", it.minutes.toString()) }
    }

    /**
     * 코드로 가입을 끝낸다 — 한 시도(가입 id)의 추측은 [AccountProperties.Verification.maxAttempts] 번이고 매번 저장소가 원자적으로 깎는다.
     * 맞으면 **이 시도의 비밀번호로** 계정을 만들고(그 주소에 계정이 이미 있으면 만들지 않는다) 같은 주소의 다른 시도를 모두 버리고 로그인할 계정을 돌려준다.
     * 없음 · 만료 · 소진 · 이미 쓴 시도 · 그 사이 주소를 가져간 계정은 모두 [AccountErrorCode.CODE_EXPIRED] 하나다.
     */
    fun verifyEmail(signUpId: String, code: String, ip: String?, ipKey: String? = ip): AuthAccount {
        val v = core.props.verification
        ipKey?.let {
            val a = core.limits.acquire("verify:ip", it, v.attemptsPerIp, v.attemptsWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        val id = core.challenges.idOf(signUpId) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        // 이 주소의 코드를 맞춰 보는 총량(가입 시도 · 이메일 변경 어느 쪽이든) — 시도를 깎기 전에 센다. 모르는 시도는 센 것이 없다
        core.challenges.find(id)?.takeIf { it.purpose == ChallengePurposes.SIGN_UP }?.let { core.spendGuess(it.subject) }
        val row = when (val checked = core.challenges.check(id, code)) {
            is CodeCheck.Ok -> checked.row
            is CodeCheck.Wrong -> throw if (checked.attemptsLeft <= 0) AccountException(AccountErrorCode.CODE_EXPIRED) else CodeInvalidException(checked.attemptsLeft)
            CodeCheck.Gone -> throw AccountException(AccountErrorCode.CODE_EXPIRED)
        }
        if (row.purpose != ChallengePurposes.SIGN_UP) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        // 시도는 한 번만 이긴다 — 같은 코드를 든 두 요청이 겹쳐도 하나
        if (!core.challenges.consume(row.id)) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val email = row.subject
        val secret = row.secret ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val (account, existed) = createOrProve(email, secret, row.payload)
        core.challenges.deleteBySubject(ChallengePurposes.SIGN_UP, email)
        core.closeSensitiveLinks(account)
        if (existed) core.sessions()?.revokeAll(account.id, null)   // 새 계정에는 닫을 세션이 없다
        core.events.publish(AccountEventType.EMAIL_VERIFIED, account.id, ip, mapOf("method" to "sign_up_code"))
        val fresh = core.accounts.findById(account.id) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        core.bootstrap.afterVerified(fresh)
        return AccountAuthRepository(core).toAuth(core.accounts.findById(account.id) ?: fresh) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
    }

    /**
     * 코드로 가입한 사람의 로그인을 **토큰이 발급된 뒤에** 기록한다 — [verifyEmail] 의 결과로 토큰 발급이 막히면(정지 · 삭제) 성공 로그인으로 남지 않는다.
     * 호출자(컨트롤러)가 `AuthTokenResponseFactory.issue` 가 던지지 않은 다음에 부른다.
     */
    fun recordSignIn(accountId: String, ip: String?, method: String = SignInMethods.PASSWORD) {
        val now = core.time.now()
        core.accounts.update(accountId, AccountPatch(lastLoginAt = now), now)
        core.events.publish(AccountEventType.LOGIN_SUCCESS, accountId, ip, mapOf("method" to method))
    }

    private fun refuseIfBlocked(email: String) {
        if (core.blocks.blocked(email)) { core.events.publish(AccountEventType.REGISTRATION_BLOCKED); throw AccountException(AccountErrorCode.REGISTRATION_BLOCKED) }
    }

    /** (계정, 이미 있던 계정이었나) */
    private fun createOrProve(email: String, secret: String, payload: String?): Pair<Account, Boolean> {
        val existing = core.accountByEmail(email)
        if (existing == null) {
            refuseIfBlocked(email)
            val profile = payload?.let { runCatching { json.readValue(it, Map::class.java) }.getOrNull() }
            val account = newAccount(email, profile?.get("displayName") as String?, profile?.get("locale") as String?, profile?.get("timeZone") as String?, unverified = false)
            val identity = passwordIdentity(account, email, secret, verified = true)
            core.atomic.run {
                if (!core.accounts.insert(account, listOf(identity))) throw AccountException(AccountErrorCode.CODE_EXPIRED)
                recordConsents(account.id, profile)
            }
            core.events.publish(AccountEventType.SIGN_UP, account.id, detail = mapOf("method" to SignInMethods.PASSWORD))
            return account to false
        }
        // 이메일 확인을 나중에 켠 앱에 남은 미확인 계정 — 메일함이 증명됐으니 그 자격은 이 시도의 비밀번호 하나만 남는다. 확인된 계정은 건드리지 않는다
        if (existing.status == AccountStatus.DELETED || existing.emailVerified) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        // 정지된 계정은 박제다 — 메일함을 증명했어도 그 계정의 자격을 갈아 끼워 이어받을 수 없다 (증명한 사람에게만 드러나는 거절)
        if (existing.status == AccountStatus.SUSPENDED) { core.events.publish(AccountEventType.REGISTRATION_BLOCKED, existing.id); throw AccountException(AccountErrorCode.REGISTRATION_BLOCKED) }
        val keep = core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.takeIf { it.accountId == existing.id }
        val proof = MailboxProof(keepIdentityIds = setOfNotNull(keep?.id), passwordSecret = secret, newPasswordIdentityId = core.newIdentityId())
        val profile = payload?.let { runCatching { json.readValue(it, Map::class.java) }.getOrNull() }
        core.atomic.run {
            if (!core.accounts.proveMailbox(existing.id, core.time.now(), proof)) throw AccountException(AccountErrorCode.CODE_EXPIRED)
            recordConsents(existing.id, profile)
        }
        return existing to true
    }

    /**
     * 같은 시도의 새 코드 — 응답은 늘 같은 모양이고 시간 값은 카운트다운용이다. 쿨다운 · 재전송 횟수 · 주소별 메일 예산을 넘으면 메일은 조용히 안 나가고 값은 **지금 유효한 코드의 것**이다.
     * **만료된 시도도** (행이 아직 있으면 — `cleanup.expired-retention`) 같은 한도로 새 코드를 받는다: 시도 id 는 그대로이고(가입을 시작한 브라우저가 든 열쇠 — 새로 줄 이유가 없다),
     * 코드 · 만료 · 남은 추측만 새로 시작한다. 한도 계산은 그대로다 — 재전송 횟수(시도당 3)와 주소별 메일 예산(`verification.per-email`)이 새 코드의 수를 묶고, 주소별 추측 천장
     * (`guesses-per-email`)이 코드마다 5번씩의 총량을 묶는다 (docs/accounts.md 무차별 대입 계산이 그대로 맞는다). 모르는 · 이미 지워진 시도는 조용히 — 값은 **보낸 것처럼 그럴듯하게**(카운트다운 힌트일 뿐, 진실은 verify-email 이다).
     * 계정 저장소 조회 · 메일은 뒤에서(요청 스레드는 시도 저장소와 한도만 만진다 — 주소가 쓰이는지 알 수 없다).
     */
    fun resendVerification(signUpId: String, ip: String?, captchaToken: String?, ipKey: String? = ip): CodeWindow {
        ipKey?.let {
            val v = core.props.verification
            val a = core.limits.acquire("resend:ip", it, v.perIp, v.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        core.captcha.check(captchaToken, ip, "resend_verification")
        val v = core.props.verification
        val id = core.challenges.idOf(signUpId)
        val row = id?.let(core.challenges::find)?.takeIf { it.purpose == ChallengePurposes.SIGN_UP } ?: return core.codeWindow(v.codeTtl)
        // 확인된 계정이 있는 주소의 시도도 **똑같이 재발급**한다 (남은 추측 · 만료 · 쿨다운 · 재전송 횟수가 같게) — 달라지면 재전송 뒤의 틀린 코드 한 번으로 가입 여부를 알 수 있다. 다른 것은 메일의 종류뿐이다
        val code = if (withinEmailBudget(row.subject)) core.challenges.reissue(row.id, v.codeTtl, v.maxAttempts, v.resendCooldown, v.maxResends) else null
        if (code == null) {
            val now = core.time.now()
            return CodeWindow(row.expiresAt, maxOf(now, row.lastSentAt.plus(v.resendCooldown)))
        }
        core.tasks.run("resend-verification") {
            val existing = core.accountByEmail(row.subject)
            if (existing != null && taken(existing)) notifyAlreadyRegistered(existing, true) else sendCode(row.subject, code, null)
        }
        val fresh = core.challenges.find(row.id) ?: return core.codeWindow(v.codeTtl)
        return CodeWindow(fresh.expiresAt, fresh.lastSentAt.plus(v.resendCooldown))
    }

    /** 시도가 실어 온 동의를 기록한다 — 호출자는 [AccountTransaction] 안이다. 고리가 없으면 아무것도 하지 않는다 */
    private fun recordConsents(accountId: String, profile: Map<*, *>?) {
        val claims = (profile?.get("consents") as? List<*>).orEmpty().mapNotNull { raw ->
            (raw as? Map<*, *>)?.let { dev.sumin.skeleton.common.consent.ConsentClaim(it["type"] as String, it["version"] as String, it["locale"] as String?) }
        }
        recordConsents(accountId, claims, profile?.get("consentIp") as String?, profile?.get("consentUa") as String?)
    }

    private fun recordConsents(accountId: String, claims: List<dev.sumin.skeleton.common.consent.ConsentClaim>, ip: String?, userAgent: String?) {
        core.consents()?.record(accountId, claims, dev.sumin.skeleton.common.consent.ConsentContext(ip, userAgent))
    }

    private fun sendCode(email: String, code: String, locale: String?) {
        val minutes = core.props.verification.codeTtl.toMinutes().toString()
        core.mailer.send(AccountMail(MailKind.VERIFY_CODE, email, locale, vars = mapOf("code" to code, "minutes" to minutes)))
    }

    /** 한 주소에 인증 코드 · "이미 계정이 있어요" 메일이 창 안에 몇 통까지 — 남의 주소로 메일 폭탄을 못 보내게. 넘으면 조용히. 이메일 변경의 대상 주소와 **같은 버킷** */
    private fun withinEmailBudget(email: String): Boolean = core.mayMailCodeTo(email)

    private fun newAccount(email: String, displayName: String?, locale: String?, timeZone: String?, unverified: Boolean): Account {
        val now = core.time.now()
        return Account(
            id = core.newAccountId(), email = email, emailVerified = !unverified, status = AccountStatus.ACTIVE, roles = core.props.defaultRoles,
            displayName = displayName, locale = locale, timeZone = timeZone, createdAt = now, updatedAt = now,
        )
    }

    private fun passwordIdentity(account: Account, email: String, hash: String, verified: Boolean) =
        Identity(core.newIdentityId(), account.id, SignInMethods.PASSWORD, email, verified, secret = hash, createdAt = account.createdAt)
}
