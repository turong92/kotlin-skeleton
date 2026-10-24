package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.CaptchaGate
import dev.sumin.skeleton.account.abuse.ExecutorAccountTaskRunner
import dev.sumin.skeleton.account.abuse.LoginRecorder
import dev.sumin.skeleton.account.abuse.LoginThrottle
import dev.sumin.skeleton.account.challenge.ChallengeStore
import dev.sumin.skeleton.account.challenge.Challenges
import dev.sumin.skeleton.account.challenge.CodeHasher
import dev.sumin.skeleton.account.challenge.InMemoryChallengeStore
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.account.events.AccountSessionEventListener
import dev.sumin.skeleton.account.events.DefaultAccountEventPublisher
import dev.sumin.skeleton.account.events.LoggingAccountEventListener
import dev.sumin.skeleton.account.mail.AccountLinks
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.account.mail.AccountMailLayout
import dev.sumin.skeleton.account.mail.AccountMailTemplates
import dev.sumin.skeleton.account.mail.DefaultAccountMailLayout
import dev.sumin.skeleton.account.mail.AccountMailTransport
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.mail.DefaultAccountMailTemplates
import dev.sumin.skeleton.account.mail.LogOnlyMailTransport
import dev.sumin.skeleton.account.mail.TemplatedAccountMailer
import dev.sumin.skeleton.account.password.BreachedPasswordCheck
import dev.sumin.skeleton.account.password.DefaultPasswordPolicy
import dev.sumin.skeleton.account.password.PasswordEncoders
import dev.sumin.skeleton.account.password.PasswordHasher
import dev.sumin.skeleton.account.password.PasswordPolicy
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.signin.SignInMethodSource
import dev.sumin.skeleton.account.token.InMemoryOneTimeTokenStore
import dev.sumin.skeleton.account.token.OneTimeTokenStore
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.config.AuthAutoConfiguration
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.session.SessionEventListener
import dev.sumin.skeleton.auth.session.SessionRevoker
import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.AuthzProperties
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RoleHolders
import dev.sumin.skeleton.common.authz.RolePermissionSource
import dev.sumin.skeleton.account.authz.AccountOperatorAccess
import dev.sumin.skeleton.account.authz.AccountPermissions
import dev.sumin.skeleton.account.authz.AccountRoleHolders
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 계정 수명주기의 서비스 · 정책 · 기본 구현. 저장소는 메모리 기본(로컬 · 시험) — 운영은 `account-jdbc` 나 앱이 같은 타입의 빈으로 바꾼다
 * (stage · prod 가드가 요구한다). 모든 빈은 [ConditionalOnMissingBean] 이라 앱이 같은 타입을 두면 이쪽이 물러난다.
 * `auth` 보다 먼저 평가해 `PasswordEncoder` 와 `AuthAccountRepository` 를 이 모듈이 정한다 — `auth` 는 그대로 진짜 계정으로 로그인한다.
 */
@AutoConfiguration(
    before = [AuthAutoConfiguration::class, AuthzAutoConfiguration::class],
    beforeName = ["dev.sumin.skeleton.auth.social.config.AuthSocialAutoConfiguration"],
    afterName = ["dev.sumin.skeleton.account.jdbc.AccountJdbcAutoConfiguration"],
)
@EnableConfigurationProperties(AccountProperties::class)
class AccountAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountRepository::class)
    fun inMemoryAccountRepository(): AccountRepository = InMemoryAccountRepository()

    @Bean
    @ConditionalOnMissingBean(AccountBlockStore::class)
    fun inMemoryAccountBlockStore(): AccountBlockStore = InMemoryAccountBlockStore()

    /** 재가입 차단 해시의 키 — `blocks.secret`, 비면 JWT 비밀에서 파생 (바뀌면 기존 차단이 맞지 않는다: docs/accounts.md) */
    @Bean
    @ConditionalOnMissingBean
    fun accountBlocks(store: AccountBlockStore, properties: AccountProperties, auth: ObjectProvider<AuthProperties>, time: ObjectProvider<TimeProvider>): AccountBlocks {
        val secret = properties.blocks.secret.ifBlank { "account-block/" + auth.getIfAvailable { AuthProperties() }.jwt.secret }
        return AccountBlocks(store, secret.toByteArray(Charsets.UTF_8), properties.blocks.retention, time.getIfAvailable { TimeProvider.systemUtc() })
    }

    @Bean
    @ConditionalOnMissingBean(AccountMaintenanceLease::class)
    fun inMemoryAccountMaintenanceLease(time: ObjectProvider<TimeProvider>): AccountMaintenanceLease = InMemoryAccountMaintenanceLease(time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean(OneTimeTokenStore::class)
    fun inMemoryOneTimeTokenStore(): OneTimeTokenStore = InMemoryOneTimeTokenStore()

    @Bean
    @ConditionalOnMissingBean(ChallengeStore::class)
    fun inMemoryChallengeStore(): ChallengeStore = InMemoryChallengeStore()

    /** 코드 해시의 키 — JWT 비밀에서 용도 접두사를 붙여 만든다 (인스턴스끼리 같고, 비밀 자체를 다른 용도에 쓰지 않는다). 키가 바뀌면 진행 중인 코드는 죽는다 */
    @Bean
    @ConditionalOnMissingBean
    fun codeHasher(auth: ObjectProvider<AuthProperties>): CodeHasher = CodeHasher(("account-code/" + auth.getIfAvailable { AuthProperties() }.jwt.secret).toByteArray(Charsets.UTF_8))

    @Bean
    @ConditionalOnMissingBean
    fun challenges(store: ChallengeStore, hasher: CodeHasher, time: ObjectProvider<TimeProvider>): Challenges =
        Challenges(store, hasher, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder::class)
    fun passwordEncoder(properties: AccountProperties): PasswordEncoder = PasswordEncoders.delegating(properties.password)

    @Bean
    @ConditionalOnMissingBean
    fun passwordHasher(encoder: PasswordEncoder): PasswordHasher = PasswordHasher(encoder)

    @Bean
    @ConditionalOnMissingBean(PasswordPolicy::class)
    fun passwordPolicy(properties: AccountProperties, breached: ObjectProvider<BreachedPasswordCheck>): PasswordPolicy =
        DefaultPasswordPolicy(properties.password, breached.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    fun accountMailLayout(properties: AccountProperties): AccountMailLayout = DefaultAccountMailLayout(properties.mail.brand)

    @Bean
    @ConditionalOnMissingBean
    fun accountMailTemplates(properties: AccountProperties, layout: AccountMailLayout): AccountMailTemplates = DefaultAccountMailTemplates(properties.mail, layout)

    @Bean
    @ConditionalOnMissingBean(AccountMailTransport::class)
    fun logOnlyAccountMailTransport(properties: AccountProperties, auth: ObjectProvider<AuthProperties>, environment: Environment): AccountMailTransport {
        val context = DeployContext.from(environment)
        val protectedProfiles = auth.getIfAvailable { AuthProperties() }.protectedProfiles
        val show = when (properties.mail.logLinks) {
            AccountProperties.Mail.LogLinks.ON -> true
            AccountProperties.Mail.LogLinks.OFF -> false
            AccountProperties.Mail.LogLinks.AUTO -> !context.protectedBy(protectedProfiles)
        }
        return LogOnlyMailTransport(show)
    }

    @Bean
    @ConditionalOnMissingBean(AccountMailer::class)
    fun accountMailer(templates: AccountMailTemplates, layout: AccountMailLayout, transport: AccountMailTransport, properties: AccountProperties, projects: ObjectProvider<ProjectRegistry>): AccountMailer {
        val registry = projects.getIfAvailable() ?: return TemplatedAccountMailer(templates, transport, properties.mail)
        // 프로젝트마다 메일 설정(브랜드 · 제목 접두사 · …)이 다르다 — 내장 틀이면 프로젝트마다 한 번 만들어 둔다. 앱이 자기 틀 빈을 두었으면 그것을 모두가 쓴다
        val mailers = registry.scopedFrom("skeleton.account", AccountProperties::class.java) { project, bound ->
            val mail = bound.mail.withOwnLinkBase(registry, project)
            // 앱이 자기 틀(templates)이나 레이아웃(layout) 빈을 두었으면 그것을 쓴다 — 내장 틀 · 레이아웃일 때만 프로젝트의 브랜드로 새로 만든다
            val projectLayout = if (layout is DefaultAccountMailLayout) DefaultAccountMailLayout(mail.brand) else layout
            TemplatedAccountMailer(if (templates is DefaultAccountMailTemplates) DefaultAccountMailTemplates(mail, projectLayout) else templates, transport, mail)
        }
        if (registry.active.size > 1 && (templates !is DefaultAccountMailTemplates || layout !is DefaultAccountMailLayout)) {
            org.slf4j.LoggerFactory.getLogger(AccountAutoConfiguration::class.java).warn(
                "The app defines its own AccountMailTemplates/AccountMailLayout, so every project ({}) sends mail with the same text and layout — the per-project skeleton.account.mail.brand only applies to the built-in ones",
                registry.active.joinToString { it.id },
            )
        }
        return AccountMailer { mailers.get().send(it) }
    }

    /** 기동 로그 한 줄 — 켜진 로그인 방법과 메일 길 (비밀 없음). 소셜 목록은 웹 자동설정이 빈을 둘 때만 있다 */
    @Bean
    @ConditionalOnMissingBean(name = ["accountStartupSummaryRunner"])
    fun accountStartupSummaryRunner(
        registry: SignInMethodRegistry,
        transport: AccountMailTransport,
        properties: AccountProperties,
        environment: Environment,
        social: ObjectProvider<dev.sumin.skeleton.account.web.SocialMethodsSource>,
        projects: ObjectProvider<ProjectRegistry>,
    ): org.springframework.boot.ApplicationRunner = org.springframework.boot.ApplicationRunner {
        val host = environment.getProperty("spring.mail.host")?.takeIf { it.isNotBlank() }?.let { h -> h + (environment.getProperty("spring.mail.port")?.let { ":$it" } ?: "") }
        org.slf4j.LoggerFactory.getLogger(AccountStartupSummary::class.java).info(
            AccountStartupSummary.describe(
                registry.all().map { it.code }, enabledSocialForSummary(social.getIfAvailable(), projects.getIfAvailable()), transport, host,
                environment.getProperty("skeleton.notification-mail.from")?.takeIf { it.isNotBlank() }, properties.mail,
            ),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun accountLinks(properties: AccountProperties, projects: ObjectProvider<ProjectRegistry>): AccountLinks {
        val registry = projects.getIfAvailable() ?: return AccountLinks(properties.mail)
        val mails = registry.scopedFrom("skeleton.account", AccountProperties::class.java) { project, p -> p.mail.withOwnLinkBase(registry, project) }
        return AccountLinks { mails.get() }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AccountTaskRunner::class)
    fun accountTaskRunner(projects: ObjectProvider<ProjectRegistry>): AccountTaskRunner =
        ExecutorAccountTaskRunner().let { if (projects.getIfAvailable() != null) dev.sumin.skeleton.account.abuse.ProjectAwareTaskRunner(it) else it }

    @Bean
    @ConditionalOnMissingBean
    fun accountRateLimits(stores: ObjectProvider<RateLimitStore>, time: ObjectProvider<TimeProvider>): AccountRateLimits {
        val fallback by lazy { InMemoryFixedWindowRateLimitStore() }
        return AccountRateLimits({ stores.getIfAvailable { fallback } }, time.getIfAvailable { TimeProvider.systemUtc() })
    }

    @Bean
    @ConditionalOnMissingBean
    fun captchaGate(captcha: ObjectProvider<AccountCaptcha>, properties: AccountProperties): CaptchaGate = CaptchaGate(captcha.getIfAvailable(), properties.captcha.required)

    /** 기본 로그 한 줄 — 이름으로만 물러난다: 앱의 리스너 · 감사 리스너가 있어도 이 줄은 남는다 */
    @Bean
    @ConditionalOnMissingBean(name = ["loggingAccountEventListener"])
    fun loggingAccountEventListener(): AccountEventListener = LoggingAccountEventListener()

    @Bean
    @ConditionalOnMissingBean(AccountEventPublisher::class)
    fun accountEventPublisher(listeners: ObjectProvider<AccountEventListener>, time: ObjectProvider<TimeProvider>): AccountEventPublisher {
        val clock = time.getIfAvailable { TimeProvider.systemUtc() }
        return DefaultAccountEventPublisher({ clock.now() }) { listeners.orderedStream().toList() }
    }

    /** `auth-session` 의 세션 사건(재사용 탐지 · 철회)을 계정 이벤트로 — 감사 표 · 경보 · 로그가 한 길을 쓴다 */
    @Bean
    @ConditionalOnMissingBean(name = ["accountSessionEventListener"])
    fun accountSessionEventListener(events: AccountEventPublisher, core: AccountCore): SessionEventListener =
        AccountSessionEventListener(events) { core.clearContactAfterRecovery(it, "sign_out_everywhere") }

    @Bean
    @ConditionalOnMissingBean
    fun oneTimeTokens(store: OneTimeTokenStore, time: ObjectProvider<TimeProvider>): OneTimeTokens =
        OneTimeTokens(store, time.getIfAvailable { TimeProvider.systemUtc() })

    /** 운영자 판정 — 토큰의 역할이 아니라 매 요청 저장소의 계정 상태 · 역할로. platform 의 토큰 기반 기본 구현보다 먼저 선다 */
    @Bean
    @ConditionalOnMissingBean
    fun accountOperatorAccess(accounts: ObjectProvider<AccountRepository>, source: ObjectProvider<RolePermissionSource>): OperatorAccess =
        AccountOperatorAccess({ accounts.getObject() }) { source.getObject() }

    @Bean
    @ConditionalOnMissingBean
    fun accountRoleHolders(accounts: ObjectProvider<AccountRepository>, events: ObjectProvider<AccountEventPublisher>, time: ObjectProvider<TimeProvider>): RoleHolders =
        AccountRoleHolders({ accounts.getObject() }, { events.getIfAvailable { AccountEventPublisher.NONE } }) { time.getIfAvailable { TimeProvider.systemUtc() }.now() }

    @Bean
    fun accountPermissions(): PermissionDeclaration = AccountPermissions.declaration

    @Bean
    @ConditionalOnMissingBean
    fun adminBootstrap(
        properties: AccountProperties,
        accounts: ObjectProvider<AccountRepository>,
        events: ObjectProvider<AccountEventPublisher>,
        time: ObjectProvider<TimeProvider>,
        authz: ObjectProvider<AuthzProperties>,
    ): AdminBootstrap = AdminBootstrap(
        properties.bootstrap, authz.getIfAvailable { AuthzProperties() }.superRole, { accounts.getObject() }, { events.getObject() }, time.getIfAvailable { TimeProvider.systemUtc() },
    )

    @Bean
    @ConditionalOnMissingBean
    fun accountCore(
        accounts: AccountRepository,
        properties: AccountProperties,
        time: ObjectProvider<TimeProvider>,
        events: AccountEventPublisher,
        hasher: PasswordHasher,
        policy: PasswordPolicy,
        tokens: OneTimeTokens,
        mailer: AccountMailer,
        links: AccountLinks,
        tasks: AccountTaskRunner,
        limits: AccountRateLimits,
        captcha: CaptchaGate,
        sessions: ObjectProvider<SessionRevoker>,
        bootstrap: AdminBootstrap,
        challenges: Challenges,
        socialReauth: ObjectProvider<SocialReauthVerifier>,
        consents: ObjectProvider<dev.sumin.skeleton.common.consent.SignUpConsentGate>,
        atomic: ObjectProvider<AccountTransaction>,
        blocks: AccountBlocks,
        lease: AccountMaintenanceLease,
        magicLinks: ObjectProvider<MagicLinkIssuer>,
        displayNameRules: DisplayNameRules,
        sessionInspector: ObjectProvider<dev.sumin.skeleton.auth.session.SessionInspector>,
        authz: ObjectProvider<AuthzProperties>,
        rolePermissions: ObjectProvider<RolePermissionSource>,
    ): AccountCore = AccountCore(
        accounts, properties, time.getIfAvailable { TimeProvider.systemUtc() }, events, hasher, policy, tokens, mailer, links, tasks, limits, captcha,
        { sessions.getIfAvailable() }, bootstrap, challenges, { socialReauth.getIfAvailable() },
        { consents.getIfAvailable() }, atomic.getIfAvailable { AccountTransaction.NONE }, blocks, lease, { magicLinks.getIfAvailable() },
        names = DisplayNames(properties.displayName, rules = displayNameRules), sessionInspector = { sessionInspector.getIfAvailable() },
        superRole = authz.getIfAvailable { AuthzProperties() }.superRole,
        roles = { rolePermissions.getObject() },
    )

    /** 닉네임 규칙 — 앱이 같은 타입의 빈을 두면 그것이 쓰인다 (가입 · 프로필 수정 · 제공자 이름 · 시드 · 비교용 키 모두) */
    @Bean
    @ConditionalOnMissingBean
    fun displayNameRules(): DisplayNameRules = DefaultDisplayNameRules

    @Bean
    @ConditionalOnMissingBean
    fun registrationService(core: AccountCore): RegistrationService = RegistrationService(core)

    @Bean
    @ConditionalOnMissingBean
    fun loginIdAvailabilityService(core: AccountCore): LoginIdAvailabilityService = LoginIdAvailabilityService(core)

    @Bean
    @ConditionalOnMissingBean
    fun loginIdService(core: AccountCore): LoginIdService = LoginIdService(core)

    @Bean
    @ConditionalOnMissingBean
    fun loginIdReminderService(core: AccountCore): LoginIdReminderService = LoginIdReminderService(core)

    @Bean
    @ConditionalOnMissingBean
    fun passwordService(core: AccountCore): PasswordService = PasswordService(core)

    @Bean
    @ConditionalOnMissingBean
    fun emailChangeService(core: AccountCore): EmailChangeService = EmailChangeService(core)

    @Bean
    @ConditionalOnMissingBean
    fun contactEmailService(core: AccountCore): ContactEmailService = ContactEmailService(core)

    /** 서비스 알림의 받는 주소: 연락 이메일 ?: 계정 이메일 (platform SPI) */
    @Bean
    @ConditionalOnMissingBean(dev.sumin.skeleton.common.contact.ContactAddresses::class)
    fun accountContactAddresses(core: AccountCore): dev.sumin.skeleton.common.contact.ContactAddresses = AccountContactAddresses(core)

    @Bean
    @ConditionalOnMissingBean
    fun reauth(core: AccountCore): Reauth = Reauth(core)

    @Bean
    @ConditionalOnMissingBean
    fun deletionService(core: AccountCore): DeletionService = DeletionService(core)

    @Bean
    @ConditionalOnMissingBean
    fun adminService(core: AccountCore): AdminService = AdminService(core)

    @Bean
    @ConditionalOnMissingBean
    fun accountPurgeService(core: AccountCore, listeners: ObjectProvider<AccountErasureListener>): AccountPurgeService =
        AccountPurgeService(core) { listeners.orderedStream().toList() }

    @Bean
    @ConditionalOnMissingBean
    fun discardService(core: AccountCore, purge: AccountPurgeService): DiscardService = DiscardService(core, purge)

    @Bean
    @ConditionalOnMissingBean(name = ["passwordSignInMethod"])
    fun passwordSignInMethod(): SignInMethod = PasswordSignInMethod()

    @Bean
    @ConditionalOnMissingBean
    fun signInMethodRegistry(methods: ObjectProvider<SignInMethod>, sources: ObjectProvider<SignInMethodSource>): SignInMethodRegistry =
        SignInMethodRegistry(methods.orderedStream().toList() + sources.orderedStream().toList().flatMap { it.methods() })

    @Bean
    @ConditionalOnMissingBean
    fun identityService(core: AccountCore, registry: SignInMethodRegistry): IdentityService = IdentityService(core, registry)

    @Bean
    @ConditionalOnMissingBean
    fun accountSignInService(core: AccountCore, registry: SignInMethodRegistry): AccountSignInService = AccountSignInService(core, registry)

    @Bean
    @ConditionalOnMissingBean
    fun profileService(core: AccountCore, registry: SignInMethodRegistry): ProfileService = ProfileService(core, registry)

    /**
     * 작성자 이름 조회의 기본 구현 — 닉네임 · 꼬리표를 `board` 같은 모듈에 내준다 (그쪽은 이 모듈을 모른다: [AuthorDirectory]).
     * 앱이 같은 타입의 빈을 두면 물러난다 (예: 돌판마다 다른 닉네임). `board` 가 없으면 아무도 부르지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean(AuthorDirectory::class)
    fun accountAuthorDirectory(accounts: AccountRepository): AuthorDirectory = AccountAuthorDirectory(accounts)

    /** `auth` 의 로그인이 진짜 계정으로 돌게 한다 — 앱이 자기 `AuthAccountRepository` 를 두면 물러난다 */
    @Bean
    @ConditionalOnMissingBean(AuthAccountRepository::class)
    fun accountAuthRepository(core: AccountCore): AuthAccountRepository = AccountAuthRepository(core)

    /** 로그인 한도 — `auth` 의 [LoginHooks] 로 모여 비밀번호 검사 앞에서 돈다 (한도 → 기록 순서) */
    @Bean
    @Order(10)
    @ConditionalOnMissingBean
    fun loginThrottle(core: AccountCore): LoginThrottle = LoginThrottle(core)

    @Bean
    @Order(20)
    @ConditionalOnMissingBean
    fun loginRecorder(core: AccountCore): LoginRecorder = LoginRecorder(core)

    /** 로컬 · e2e 시드 계정 (`skeleton.account.seed.accounts`) — 비어 있으면 아무것도 하지 않는다 */
    @Bean
    @ConditionalOnMissingBean
    fun accountSeeder(core: AccountCore, projects: ObjectProvider<ProjectRegistry>): AccountSeeder =
        AccountSeeder(core, projects.getIfAvailable()?.let { r -> AccountSeeder.ProjectSeeds(r) { p -> r.bind(p, "skeleton.account", AccountProperties::class.java).seed.accounts } })

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["accountPurgeScheduler"])
    fun accountPurgeScheduler(properties: AccountProperties, dispatch: AccountPurgeDispatch): AccountPurgeScheduler =
        AccountPurgeScheduler(properties.deletion.purgeInterval, dispatch)

    /** 요청 구동 모드(`skeleton.job-queue.mode=on-request`)의 깨우기 주소가 이 정리를 한 번 깨울 수 있게 내놓는다 — 타이머가 없는 0대 배포용 (docs/cloud-run-scale-to-zero.md). 다른 곳이 부르지 않으면 쓰이지 않는다 */
    @Bean
    @ConditionalOnMissingBean(name = ["accountPurgeOnRequestTask"])
    fun accountPurgeOnRequestTask(properties: AccountProperties, dispatch: AccountPurgeDispatch): dev.sumin.skeleton.common.wake.OnRequestTask =
        // 주기를 0 으로 꺼 둔 앱(`deletion.purge-interval` — 정리는 앱이 직접 부른다)은 깨우기도 이 정리를 돌리지 않는다. 값은 실행 때 읽는다
        dev.sumin.skeleton.common.wake.SimpleOnRequestTask("account-purge") {
            val interval = properties.deletion.purgeInterval
            if (!interval.isZero && !interval.isNegative) dispatch.dispatch()
        }

    /** 주기마다 직접 지운다 — `job-queue-jdbc` 가 있으면 [AccountPurgeJobAutoConfiguration] 이 잡 넣기로 바꾼다 */
    @Bean
    @ConditionalOnMissingBean(AccountPurgeDispatch::class)
    fun accountPurgeDispatch(purge: AccountPurgeService, projects: ObjectProvider<ProjectRegistry>): AccountPurgeDispatch =
        projects.getIfAvailable()?.let { r -> AccountPurgeDispatch { r.forEachProject { purge.purgeDue() } } } ?: AccountPurgeDispatch { purge.purgeDue() }

    @Bean
    @ConditionalOnMissingBean
    fun accountDeployGuard(
        properties: AccountProperties,
        auth: ObjectProvider<AuthProperties>,
        accounts: ObjectProvider<AccountRepository>,
        tokenStore: ObjectProvider<OneTimeTokenStore>,
        challengeStore: ObjectProvider<ChallengeStore>,
        transport: ObjectProvider<AccountMailTransport>,
        captcha: ObjectProvider<AccountCaptcha>,
        clientIps: ObjectProvider<ClientIps>,
        registry: ObjectProvider<SignInMethodRegistry>,
        projects: ObjectProvider<ProjectRegistry>,
    ): AccountDeployGuard = AccountDeployGuard(
        properties, auth.getIfAvailable { AuthProperties() }.protectedProfiles,
        // 프로젝트가 있으면 프로젝트마다 같은 검사 — 시드 · 로그 링크는 공통+프로젝트 값, 링크 주소는 프로젝트 자기 값만(공통으로 내려가지 않는다)
        projects.getIfAvailable()?.active.orEmpty().map { p ->
            AccountDeployGuard.ProjectView(p.id, projects.getObject().bind(p, "skeleton.account", AccountProperties::class.java), projects.getObject().bindOwn(p, "skeleton.account", AccountProperties::class.java).mail.linkBaseUrl)
        },
    ) {
        AccountDeployGuard.State(
            accounts.getIfUnique(), tokenStore.getIfUnique(), challengeStore.getIfUnique(), transport.getIfUnique() is LogOnlyMailTransport, captcha.getIfAvailable() != null,
            clientIps.getIfAvailable { ClientIps() }.configured,
            registry.getIfAvailable()?.all()?.any { it.provesEmail } == true,
        )
    }
}

/** 프로젝트 모드의 링크 주소는 그 프로젝트가 정한 값만 — 비면 공통(다른 프로젝트의 도메인일 수 있다)으로 내려가지 않고 비워 둔다. 가드가 보호 환경에서 막는다 */
/** 기동 로그용 켜진 소셜 목록 — 기동에는 프로젝트 문맥이 없으니 프로젝트 모드에서는 프로젝트마다 그 문맥에서 모아 제공자별로 하나만 남긴다 */
internal fun enabledSocialForSummary(source: dev.sumin.skeleton.account.web.SocialMethodsSource?, projects: ProjectRegistry?): List<dev.sumin.skeleton.account.web.SocialMethodView> {
    source ?: return emptyList()
    projects ?: return source.enabled()
    return projects.active.flatMap { project -> dev.sumin.skeleton.common.project.ProjectContext.runIn(project) { source.enabled() } }.distinctBy { it.provider }
}

private fun AccountProperties.Mail.withOwnLinkBase(registry: ProjectRegistry, project: dev.sumin.skeleton.common.project.Project): AccountProperties.Mail =
    copy(linkBaseUrl = registry.bindOwn(project, "skeleton.account", AccountProperties::class.java).mail.linkBaseUrl)
