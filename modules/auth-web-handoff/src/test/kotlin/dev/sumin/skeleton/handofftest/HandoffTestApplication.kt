package dev.sumin.skeleton.handofftest

import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.auth.session.SessionInspector
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class HandoffTestApplication

class MutableTime(@Volatile var at: Instant = Instant.parse("2026-11-04T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = at
    fun advance(d: Duration) { at = at.plus(d) }
}

/** 열린 세션 = 시험이 연 것(세션 id -> 계정 id). 닫으면 목록에서 뺀다 */
class FakeSessionInspector : SessionInspector {
    val open = ConcurrentHashMap<String, String>()
    fun open(accountId: String): String = "sid-${System.nanoTime()}".also { open[it] = accountId }
    override fun openedAt(accountId: String, sessionId: String): Instant? = if (open[sessionId] == accountId) Instant.parse("2026-11-04T00:00:00Z") else null
}

/** 시험 계정을 만들 수단 하나 - 증명이 곧 메일함 소유(매직 링크와 같은 모양) */
class TestSignInMethod : SignInMethod {
    override val code: String = "handoff_test"
    override val provesEmail: Boolean = true
}

@TestConfiguration(proxyBeanMethods = false)
class HandoffTestBeans {
    @Bean fun mailer(): AccountMailer = object : AccountMailer { override fun send(mail: AccountMail) {} }
    @Bean fun time(): MutableTime = MutableTime()
    @Bean fun accountTaskRunner(): AccountTaskRunner = AccountTaskRunner.DIRECT
    @Bean fun sessionInspector(): FakeSessionInspector = FakeSessionInspector()
    @Bean fun testSignInMethod(): SignInMethod = TestSignInMethod()
}
