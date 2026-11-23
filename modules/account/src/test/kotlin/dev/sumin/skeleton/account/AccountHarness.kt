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

/** 로그인으로 열린 세션의 기록 — [open] 이 "지금 로그인했다" 이고, 닫힌 · 모르는 세션은 null */
class FakeSessions(private val time: MutableTime) : dev.sumin.skeleton.auth.session.SessionInspector {
    private val opened = java.util.concurrent.ConcurrentHashMap<String, Pair<String, java.time.Instant>>()
    fun open(accountId: String, sessionId: String) { opened[sessionId] = accountId to time.now() }
    override fun openedAt(accountId: String, sessionId: String): java.time.Instant? = opened[sessionId]?.takeIf { it.first == accountId }?.second
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
    /** 저장소를 바꿔 끼운다 (예: 악센트 · 대소문자를 같게 보는 DB 정렬을 흉내 낸 것) — 호출 기록 프록시가 이것을 감싼다 */
    storage: AccountRepository? = null,
    /** 비밀번호 포트 — null 이면 `account-password` 가 없는 서비스 */
    passwordCredentials: PasswordCredentials? = FakePasswordCredentials(),
    socialReauth: SocialReauthVerifier? = null,
    consents: dev.sumin.skeleton.common.consent.SignUpConsentGate? = null,
    atomic: AccountTransaction = AccountTransaction.NONE,
    /** 역할 → 권한 표를 바꿔 끼운다(기본은 super-role + [rolePermissions] 의 yml 모양) */
    roleSource: dev.sumin.skeleton.common.authz.RolePermissionSource? = null,
    /** 매직 링크 수단(`auth-magic-link`)이 있을 때만 있는 고리 — "이미 계정이 있어요" 메일의 1회용 로그인 링크 */
    magicLinks: MagicLinkIssuer? = null,
    /** 꼬리표 · 자동 닉네임의 난수 — 시험이 고정하려고 */
    random: java.util.random.RandomGenerator = java.security.SecureRandom(),
    /** 전권 역할 이름 (`skeleton.authz.super-role`) */
    val superRole: String = "ADMIN",
    /** 전권 역할 말고 정의된 역할 → 권한 (`skeleton.authz.roles`) */
    rolePermissions: Map<String, Set<String>> = emptyMap(),
    /** 삭제 요청이 모으는 다른 모듈의 막는 이유 (`AccountDeletionCheck`) */
    deletionChecks: List<dev.sumin.skeleton.common.account.AccountDeletionCheck> = emptyList(),
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
    val sessions = FakeSessions(time)
    val tokens = OneTimeTokens(tokenStore, time)
    val publisher = DefaultAccountEventPublisher({ time.now() }) { listOf(events) }
    private val limitStore = dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore()
    val bootstrap = AdminBootstrap(props.bootstrap, superRole, { repo }, { publisher }, time)
    val core = AccountCore(
        repo, props, time, publisher, tokens, mailer, AccountLinks(props.mail), tasks,
        AccountRateLimits({ limitStore }, time),
        CaptchaGate(captcha, captchaRequired), { revoker }, bootstrap, challenges, { socialReauth }, { consents }, atomic,
        AccountBlocks.local(time, props.blocks.retention), magicLinks = { magicLinks }, names = DisplayNames(props.displayName, random), sessionInspector = { sessions }, superRole = superRole,
        roles = { roleSource ?: dev.sumin.skeleton.common.authz.YmlRolePermissionSource(mapOf(superRole to setOf("*")) + rolePermissions) },
        deletionChecks = { deletionChecks }, passwords = { credentials },
    )
    /** [FakePasswordCredentials] — `account-password` 없이 비밀번호 줄을 흉내 낸다 */
    val credentials: PasswordCredentials? = passwordCredentials
    val availability = LoginIdAvailabilityService(core)
    val loginIds = LoginIdService(core)
    val reminder = LoginIdReminderService(core)
    val authRepository = AccountAuthRepository(core)
    val emailChange = EmailChangeService(core)
    val contact = ContactEmailService(core)
    val reauth = Reauth(core)
    val erasers = java.util.concurrent.CopyOnWriteArrayList<dev.sumin.skeleton.common.erasure.AccountErasureListener>()
    val deletion = DeletionService(core)
    val purge = AccountPurgeService(core) { erasers.toList() }
    val admin = AdminService(core)
    val discard = DiscardService(core, purge)
    val profile = ProfileService(core, dev.sumin.skeleton.account.signin.SignInMethodRegistry(listOf(FakePasswordSignInMethod())))


    /**
     * 비밀번호 가입 없이 계정 하나 — 가입 · 코드 확인은 `account-password` 의 일이다. 기존 시드 장치([AccountSeeder])를 그대로 써서 확인된 ACTIVE 계정과 비밀번호 줄
     * (해시는 [FakePasswordCredentials])을 저장소에 바로 넣는다. 이름 · 닉네임 · 아이디 규칙은 가입과 같은 [AccountCore.insertNamed] 를 지난다.
     * [password] 가 null 이면 비밀번호 줄이 없는 계정(메일 코드로 다시 인증)을 — 매직 링크 수단 한 줄만 달고 — 만든다.
     */
    fun activeAccount(
        email: String = "ann@example.com", password: String? = "tangerine-42-moon", displayName: String? = "Ann", loginId: String? = null, locale: String? = null,
    ): Account {
        if (password != null) {
            AccountSeeder(core).seed(listOf(AccountProperties.SeedAccount(email = email, password = password, displayName = displayName, loginId = loginId, locale = locale)))
        } else {
            val normalized = Emails.normalize(email)
            val now = time.now()
            val base = Account(
                id = core.newAccountId(), email = normalized, emailVerified = true, status = AccountStatus.ACTIVE, roles = core.props.defaultRoles,
                displayName = null, locale = ProfileRules.locale(locale), timeZone = null, createdAt = now, updatedAt = now,
            ).copy(loginId = loginId?.let(core::acceptLoginId))
            val identity = Identity(core.newIdentityId(), base.id, SignInMethods.MAGIC_LINK, normalized, true, createdAt = now)
            core.insertNamed(base, listOf(identity), core.names.sanitize(displayName), strict = false) { repo.insert(it, listOf(identity), core.loginIdHashOf(it.loginId)) }
        }
        return repo.findByEmail(Emails.normalize(email))!!.also { bootstrap.afterVerified(it) }
    }

    /** 이메일 확인 없이 가입한 앱이 남기는 계정 — ACTIVE 지만 주소는 증명되지 않았고 비밀번호 줄도 미확인이다 (`account-password` 의 가입 없이 같은 상태를 만든다) */
    fun unverifiedAccount(email: String = "ann@example.com", password: String = "tangerine-42-moon"): Account {
        val normalized = Emails.normalize(email)
        val now = time.now()
        val base = Account(core.newAccountId(), normalized, false, AccountStatus.ACTIVE, core.props.defaultRoles, null, null, null, now, now)
        repo.insert(base, listOf(Identity(core.newIdentityId(), base.id, SignInMethods.PASSWORD, normalized, false, secret = credentials!!.hash(password), createdAt = now)))
        return repo.findById(base.id)!!
    }

    fun mailCount() = mailer.sent.size
    fun oneHour(): Duration = Duration.ofHours(1)
}
