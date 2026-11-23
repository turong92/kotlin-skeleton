package dev.sumin.skeleton.accounttest

import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
// job-queue-jdbc(잡 연동 시험용)가 시험 클래스패스에 spring-jdbc 를 끌고 오므로 DataSource 자동설정은 끈다 — 이 시험들은 DB 가 필요 없다
@SpringBootApplication(excludeName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
class AccountWebTestApplication

/** 메일은 기록하고 · 뒤로 넘기는 일은 그 자리에서 — 응답 직후 결과를 볼 수 있게 */
@TestConfiguration(proxyBeanMethods = false)
class AccountTestBeans {
    @Bean fun recordingMailer(): RecordingMailer = RecordingMailer()
    @Bean fun accountTaskRunner(): AccountTaskRunner = AccountTaskRunner.DIRECT

    /** `account-password` 없이도 비밀번호 줄이 있는 계정을 시험하는 흉내 — 해시는 `fake:` + 원문 (비밀번호 흐름 자체는 `account-password` 의 시험) */
    @Bean fun fakePasswordCredentials(): dev.sumin.skeleton.account.PasswordCredentials = dev.sumin.skeleton.account.FakePasswordCredentials()
    @Bean fun fakePasswordSignInMethod(): dev.sumin.skeleton.account.signin.SignInMethod = dev.sumin.skeleton.account.FakePasswordSignInMethod()

    @Bean fun accountFixtures(
        core: dev.sumin.skeleton.account.AccountCore,
        accounts: dev.sumin.skeleton.account.AccountRepository,
        authAccounts: dev.sumin.skeleton.auth.account.AuthAccountRepository,
        tokens: dev.sumin.skeleton.auth.api.AuthTokenResponseFactory,
    ): AccountFixtures = AccountFixtures(core, accounts, authAccounts, tokens)
}

/**
 * 비밀번호 가입 · 로그인 없이 계정과 토큰을 만드는 시험 장치 — 이 레포의 「비밀번호 없이 계정 만들기」 표준. 가입(`/account/sign-up`)은 `account-password` 의 길이라
 * 형제 모듈에 시험 의존을 걸지 않으려면 쓸 수 없고, 메일 코드 · 로그인 호출 왕복도 빌 필요가 없다:
 *  - 계정: 기존 시드 장치([dev.sumin.skeleton.account.AccountSeeder]) — 확인된 ACTIVE 계정, 닉네임 · 아이디 규칙은 가입과 같은 길 (비밀번호 줄은 [dev.sumin.skeleton.account.FakePasswordCredentials])
 *  - 토큰: 앱이 쓰는 그 팩토리([dev.sumin.skeleton.auth.api.AuthTokenResponseFactory]) — 정지 · 2단계 고리 · 세션 열기가 진짜 로그인과 같다
 */
class AccountFixtures(
    private val core: dev.sumin.skeleton.account.AccountCore,
    private val accounts: dev.sumin.skeleton.account.AccountRepository,
    private val authAccounts: dev.sumin.skeleton.auth.account.AuthAccountRepository,
    private val tokens: dev.sumin.skeleton.auth.api.AuthTokenResponseFactory,
) {
    fun create(email: String, loginId: String? = null, displayName: String? = null, password: String = "tangerine-42-moon", locale: String? = "ko"): dev.sumin.skeleton.account.Account {
        dev.sumin.skeleton.account.AccountSeeder(core).seed(listOf(dev.sumin.skeleton.account.AccountProperties.SeedAccount(email = email, password = password, displayName = displayName, loginId = loginId, locale = locale)))
        return accounts.findByEmail(dev.sumin.skeleton.account.Emails.normalize(email))!!.also { core.bootstrap.afterVerified(it) }   // 가입 확인과 같게: 첫 관리자 부트스트랩이 본다
    }

    /** 계정 id 로 직접 — 이메일 없는 계정(소셜로만 들어온 것)도 토큰은 받을 수 있다 */
    fun tokenOfAccount(accountId: String): dev.sumin.skeleton.auth.api.AuthTokenResponse =
        tokens.issue(authAccounts.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(accountId = accountId))!!)

    fun token(email: String): dev.sumin.skeleton.auth.api.AuthTokenResponse =
        tokens.issue(authAccounts.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(email = dev.sumin.skeleton.account.Emails.normalize(email)))!!)

    fun bearer(email: String): String = "Bearer " + token(email).accessToken
    fun bearerOf(accountId: String): String = "Bearer " + tokenOfAccount(accountId).accessToken
}

/** 세션 저장소 없이 `auth-session` 의 계약만 흉내 낸다 — 로그인이 세션을 열고(`sid` 가 액세스 토큰에 실린다), 열린 시각을 읽을 수 있고, 시험이 세션을 "오래된 것" 으로 돌릴 수 있다 */
class FakeSessionBook : dev.sumin.skeleton.auth.session.LoginSessionIssuer, dev.sumin.skeleton.auth.session.SessionInspector {
    private val opened = java.util.concurrent.ConcurrentHashMap<String, Pair<String, java.time.Instant>>()
    private val counter = java.util.concurrent.atomic.AtomicInteger()

    override fun open(account: dev.sumin.skeleton.auth.account.AuthAccount): dev.sumin.skeleton.auth.session.OpenedSession {
        val id = "ses_fake_${counter.incrementAndGet()}"
        opened[id] = account.accountId to java.time.Instant.now()
        return dev.sumin.skeleton.auth.session.OpenedSession(id)
    }

    override fun openedAt(accountId: String, sessionId: String): java.time.Instant? = opened[sessionId]?.takeIf { it.first == accountId }?.second

    /** 이 세션이 [by] 전에 열린 것으로 돌린다 */
    fun age(sessionId: String, by: java.time.Duration) { opened.computeIfPresent(sessionId) { _, v -> v.first to v.second.minus(by) } }
    fun sessionsOf(accountId: String) = opened.filterValues { it.first == accountId }.keys.sorted()
}

@TestConfiguration(proxyBeanMethods = false)
class FakeSessionBeans {
    @Bean fun fakeSessionBook(): FakeSessionBook = FakeSessionBook()
}
