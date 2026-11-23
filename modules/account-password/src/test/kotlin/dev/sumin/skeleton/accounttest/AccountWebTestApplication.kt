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
