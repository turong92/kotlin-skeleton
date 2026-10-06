package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.CaptchaGate
import dev.sumin.skeleton.account.challenge.Challenges
import dev.sumin.skeleton.account.challenge.CodeHasher
import dev.sumin.skeleton.account.challenge.InMemoryChallengeStore
import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.DefaultAccountEventPublisher
import dev.sumin.skeleton.account.mail.AccountLinks
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.password.BreachedPasswordCheck
import dev.sumin.skeleton.account.password.DefaultPasswordPolicy
import dev.sumin.skeleton.account.password.PasswordEncoders
import dev.sumin.skeleton.account.password.PasswordHasher
import dev.sumin.skeleton.account.token.InMemoryOneTimeTokenStore
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.auth.session.SessionRevoker
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class RecordingMailer : AccountMailer {
    val sent = CopyOnWriteArrayList<AccountMail>()
    override fun send(mail: AccountMail) { sent += mail }
    fun of(kind: dev.sumin.skeleton.account.mail.MailKind) = sent.filter { it.kind == kind }
    fun tokenOf(mail: AccountMail): String = mail.link!!.substringAfter("token=")
}

class RecordingRevoker : SessionRevoker {
    val calls = CopyOnWriteArrayList<Pair<String, String?>>()
    override fun revokeAll(accountId: String, exceptSessionId: String?) { calls += accountId to exceptSessionId }
}

class RecordingEvents : AccountEventListener {
    val all = CopyOnWriteArrayList<AccountEvent>()
    override fun on(event: AccountEvent) { all += event }
    fun types() = all.map { it.type }
}

/** 어느 스레드가 어느 저장소 메서드를 불렀는지 — "요청 스레드는 계정 저장소를 건드리지 않는다" 를 보이려고 */
class CallLog { val calls = CopyOnWriteArrayList<Pair<Thread, String>>(); fun by(thread: Thread) = calls.filter { it.first == thread }.map { it.second } }

class AccountHarness(
    props: AccountProperties = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
        password = AccountProperties.Password(bcryptStrength = 4),
    ),
    tasks: AccountTaskRunner = AccountTaskRunner.DIRECT,
    captcha: AccountCaptcha? = null,
    captchaRequired: Boolean = captcha != null,
    breached: BreachedPasswordCheck? = null,
    /** 저장소를 바꿔 끼운다 (예: 악센트 · 대소문자를 같게 보는 DB 정렬을 흉내 낸 것) — 호출 기록 프록시가 이것을 감싼다 */
    storage: AccountRepository? = null,
    socialReauth: SocialReauthVerifier? = null,
) {
    val time = MutableTime()
    val callLog = CallLog()
    val repo: AccountRepository = run {
        val real = storage ?: InMemoryAccountRepository()
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccountRepository::class.java)) { _, m, args ->
            callLog.calls += Thread.currentThread() to m.name
            try { m.invoke(real, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as AccountRepository
    }
    val tokenStore = InMemoryOneTimeTokenStore()
    val challengeStore = InMemoryChallengeStore()
    val challenges = Challenges(challengeStore, CodeHasher(ByteArray(32) { 7 }), time)
    val mailer = RecordingMailer()
    val events = RecordingEvents()
    val revoker = RecordingRevoker()
    val hasher = PasswordHasher(PasswordEncoders.delegating(props.password))
    val policy = DefaultPasswordPolicy(props.password, breached)
    val tokens = OneTimeTokens(tokenStore, time)
    val publisher = DefaultAccountEventPublisher({ time.now() }) { listOf(events) }
    private val limitStore = dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore()
    val bootstrap = AdminBootstrap(props.bootstrap, props.admin.role, { repo }, { publisher }, time)
    val core = AccountCore(
        repo, props, time, publisher, hasher, policy, tokens, mailer, AccountLinks(props.mail), tasks,
        AccountRateLimits({ limitStore }, time),
        CaptchaGate(captcha, captchaRequired), { revoker }, bootstrap, challenges, { socialReauth },
    )
    val registration = RegistrationService(core)
    val authRepository = AccountAuthRepository(core)
    val passwords = PasswordService(core)
    val emailChange = EmailChangeService(core)
    val reauth = Reauth(core)
    val erasers = java.util.concurrent.CopyOnWriteArrayList<dev.sumin.skeleton.common.erasure.AccountErasureListener>()
    val deletion = DeletionService(core)
    val purge = AccountPurgeService(core) { erasers.toList() }
    val admin = AdminService(core)
    val profile = ProfileService(core, dev.sumin.skeleton.account.signin.SignInMethodRegistry(listOf(dev.sumin.skeleton.account.signin.PasswordSignInMethod())))

    /** 이메일별로 마지막 가입 id — [verify] · [resend] 가 쓴다 */
    val signUpIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun signUp(email: String = "ann@example.com", password: String = "tangerine-42-moon", ip: String? = "203.0.113.1", captchaToken: String? = null, locale: String? = null): SignUpOutcome =
        registration.signUp(SignUpCommand(email, password, "Ann", locale, "Asia/Seoul", ip, captchaToken)).also { o -> o.signUpId?.let { signUpIds[Emails.normalize(email)] = it } }

    /** 그 주소로 마지막에 나간 인증 코드 메일의 코드 */
    fun lastCode(email: String = "ann@example.com"): String =
        mailer.sent.last { it.kind == dev.sumin.skeleton.account.mail.MailKind.VERIFY_CODE && it.to == Emails.normalize(email) }.vars.getValue("code")

    /** 마지막 가입 시도를 마지막 코드로 확인 — 가입이 끝나 계정이 생긴다 */
    fun verify(email: String = "ann@example.com", ip: String? = "203.0.113.1") =
        registration.verifyEmail(signUpIds.getValue(Emails.normalize(email)), lastCode(email), ip)

    /** 가입 + 확인까지 끝낸 계정 */
    fun activeAccount(email: String = "ann@example.com", password: String = "tangerine-42-moon"): Account {
        signUp(email, password); verify(email)
        return repo.findByEmail(Emails.normalize(email))!!
    }

    fun mailCount() = mailer.sent.size
    fun oneHour(): Duration = Duration.ofHours(1)
}
