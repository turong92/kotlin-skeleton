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
) {
    override fun toString() = "SignUpCommand(email=<redacted>, password=<redacted>)"
}

enum class SignUpStatus { VERIFICATION_SENT, CREATED }

/** [signUpId]: 코드 확인 · 재전송에 쓰는 가입 시도 id — 이메일 확인을 끈 앱(CREATED)에는 없다 */
data class SignUpOutcome(val status: SignUpStatus, val signUpId: String? = null)

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

        if (!p.signUp.emailVerification) return SignUpOutcome(createVerified(email, hash, cmd))

        val v = p.verification
        val mayOpen = core.limits.acquire("signup:email", email, v.signUpAttemptsPerEmail, v.perEmailWindow).allowed
        val mayMail = withinEmailBudget(email)
        val profile = json.writeValueAsString(
            mapOf("displayName" to ProfileRules.displayName(cmd.displayName), "locale" to ProfileRules.locale(cmd.locale), "timeZone" to ProfileRules.timeZone(cmd.timeZone)),
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
        return SignUpOutcome(SignUpStatus.VERIFICATION_SENT, opened.handle)
    }

    private fun createVerified(email: String, hash: String, cmd: SignUpCommand): SignUpStatus {
        if (core.accountByEmail(email) != null) throw AccountException(AccountErrorCode.EMAIL_TAKEN)
        val account = newAccount(email, ProfileRules.displayName(cmd.displayName), ProfileRules.locale(cmd.locale), ProfileRules.timeZone(cmd.timeZone), unverified = true)
        if (!core.accounts.insert(account, listOf(passwordIdentity(account, email, hash, verified = false)))) throw AccountException(AccountErrorCode.EMAIL_TAKEN)
        core.events.publish(AccountEventType.SIGN_UP, account.id, cmd.ip, mapOf("method" to SignInMethods.PASSWORD))
        return SignUpStatus.CREATED
    }

    /** 확인된(또는 지워지는 중인) 계정이 가진 주소 — 코드로 가져갈 수 없다. 확인되지 않은 계정(이메일 확인을 나중에 켠 앱에 남은 것)은 코드로 메일함을 증명하면 이어받는다 */
    private fun taken(existing: Account) = existing.emailVerified || existing.status == AccountStatus.DELETED

    private fun notifyAlreadyRegistered(existing: Account, allowed: Boolean) {
        val address = existing.email ?: return
        if (existing.status == AccountStatus.DELETED || !allowed) return
        core.mailer.send(AccountMail(MailKind.ALREADY_REGISTERED, address, existing.locale))
    }

    /**
     * 코드로 가입을 끝낸다 — 한 시도(가입 id)의 추측은 [AccountProperties.Verification.maxAttempts] 번이고 매번 저장소가 원자적으로 깎는다.
     * 맞으면 **이 시도의 비밀번호로** 계정을 만들고(그 주소에 계정이 이미 있으면 만들지 않는다) 같은 주소의 다른 시도를 모두 버리고 로그인할 계정을 돌려준다.
     * 없음 · 만료 · 소진 · 이미 쓴 시도 · 그 사이 주소를 가져간 계정은 모두 [AccountErrorCode.CODE_EXPIRED] 하나다.
     */
    fun verifyEmail(signUpId: String, code: String, ip: String?): AuthAccount {
        val v = core.props.verification
        ip?.let {
            val a = core.limits.acquire("verify:ip", it, v.attemptsPerIp, v.attemptsWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        val id = core.challenges.idOf(signUpId) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
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
        val now = core.time.now()
        core.accounts.update(account.id, AccountPatch(lastLoginAt = now), now)
        core.events.publish(AccountEventType.EMAIL_VERIFIED, account.id, ip, mapOf("method" to "sign_up_code"))
        core.events.publish(AccountEventType.LOGIN_SUCCESS, account.id, ip, mapOf("method" to SignInMethods.PASSWORD))
        val fresh = core.accounts.findById(account.id) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
        core.bootstrap.afterVerified(fresh)
        return AccountAuthRepository(core).toAuth(core.accounts.findById(account.id) ?: fresh) ?: throw AccountException(AccountErrorCode.CODE_EXPIRED)
    }

    /** (계정, 이미 있던 계정이었나) */
    private fun createOrProve(email: String, secret: String, payload: String?): Pair<Account, Boolean> {
        val existing = core.accountByEmail(email)
        if (existing == null) {
            val profile = payload?.let { runCatching { json.readValue(it, Map::class.java) }.getOrNull() }
            val account = newAccount(email, profile?.get("displayName") as String?, profile?.get("locale") as String?, profile?.get("timeZone") as String?, unverified = false)
            val identity = passwordIdentity(account, email, secret, verified = true)
            if (!core.accounts.insert(account, listOf(identity))) throw AccountException(AccountErrorCode.CODE_EXPIRED)
            core.events.publish(AccountEventType.SIGN_UP, account.id, detail = mapOf("method" to SignInMethods.PASSWORD))
            return account to false
        }
        // 이메일 확인을 나중에 켠 앱에 남은 미확인 계정 — 메일함이 증명됐으니 그 자격은 이 시도의 비밀번호 하나만 남는다. 확인된 계정은 건드리지 않는다
        if (existing.status == AccountStatus.DELETED || existing.emailVerified) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        val keep = core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.takeIf { it.accountId == existing.id }
        val proof = MailboxProof(keepIdentityIds = setOfNotNull(keep?.id), passwordSecret = secret, newPasswordIdentityId = core.newIdentityId())
        if (!core.accounts.proveMailbox(existing.id, core.time.now(), proof)) throw AccountException(AccountErrorCode.CODE_EXPIRED)
        return existing to true
    }

    /** 항상 같은 응답 — 시도 조회 · 메일은 뒤에서, 쿨다운 · 재전송 한도 · 주소별 예산을 넘으면 조용히 */
    fun resendVerification(signUpId: String, ip: String?, captchaToken: String?) {
        ip?.let {
            val v = core.props.verification
            val a = core.limits.acquire("resend:ip", it, v.perIp, v.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        core.captcha.check(captchaToken, ip, "resend_verification")
        val id = core.challenges.idOf(signUpId)
        core.tasks.run("resend-verification") {
            val row = id?.let(core.challenges::find)?.takeIf { it.purpose == ChallengePurposes.SIGN_UP } ?: return@run
            val existing = core.accountByEmail(row.subject)
            if (existing != null && taken(existing)) return@run notifyAlreadyRegistered(existing, withinEmailBudget(row.subject))
            if (!withinEmailBudget(row.subject)) return@run
            val v = core.props.verification
            core.challenges.reissue(row.id, v.codeTtl, v.maxAttempts, v.resendCooldown, v.maxResends)?.let { sendCode(row.subject, it, null) }
        }
    }

    private fun sendCode(email: String, code: String, locale: String?) {
        val minutes = core.props.verification.codeTtl.toMinutes().toString()
        core.mailer.send(AccountMail(MailKind.VERIFY_CODE, email, locale, vars = mapOf("code" to code, "minutes" to minutes)))
    }

    /** 한 주소에 인증 코드 · "이미 계정이 있어요" 메일이 창 안에 몇 통까지 — 남의 주소로 메일 폭탄을 못 보내게. 넘으면 조용히 */
    private fun withinEmailBudget(email: String): Boolean {
        val v = core.props.verification
        return core.limits.acquire("verification:email", email, v.perEmail, v.perEmailWindow).allowed
    }

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
