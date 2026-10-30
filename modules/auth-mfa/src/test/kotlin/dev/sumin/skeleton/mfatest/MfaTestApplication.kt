package dev.sumin.skeleton.mfatest

import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.auth.mfa.FakeMfaStore
import dev.sumin.skeleton.auth.session.SessionInspector
import dev.sumin.skeleton.auth.session.SessionRevoker
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 - 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class MfaTestApplication

class Mails : AccountMailer {
    val sent = CopyOnWriteArrayList<AccountMail>()
    override fun send(mail: AccountMail) { sent += mail }
    fun lastCode(): String = sent.last { it.kind == MailKind.VERIFY_CODE }.vars.getValue("code")
    fun notices(to: String, change: String) = sent.filter { it.kind == MailKind.MFA_NOTICE && it.to == to && it.vars["change"] == change }
}

class Events : AccountEventListener {
    val all = CopyOnWriteArrayList<AccountEvent>()
    override fun on(event: AccountEvent) { all += event }
    fun of(type: AccountEventType, accountId: String) = all.filter { it.type == type && it.accountId == accountId }
}

/** 어떤 세션을 닫았나 - (계정, 남긴 세션) */
class FakeSessions : SessionRevoker {
    val revoked = CopyOnWriteArrayList<Pair<String, String?>>()
    override fun revokeAll(accountId: String, exceptSessionId: String?) { revoked += accountId to exceptSessionId }
}

class Audits : OperatorAuditLog {
    val all = CopyOnWriteArrayList<OperatorAction>()
    override fun record(action: OperatorAction) { all += action }
}

/** 세션 id -> 열린 시각. 시험이 채운다 - 없는 세션은 열려 있지 않다 */
class FakeSessionInspector : SessionInspector {
    val openedAt = ConcurrentHashMap<String, Instant>()
    override fun openedAt(accountId: String, sessionId: String): Instant? = openedAt[sessionId]
}

/** 시험이 시계를 쥔다 - 한 걸음(30초) 안에서 서버와 시험이 같은 코드를 보게. JWT 는 시스템 시계로 검증되므로 시작은 지금이고 조금씩만 앞으로 간다 */
class FrozenTime(@Volatile var at: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)) : TimeProvider {
    override fun now(): Instant = at
    fun advance(d: Duration) { at = at.plus(d) }
}

@TestConfiguration(proxyBeanMethods = false)
class MfaTestBeans {
    @Bean fun fakeMfaStore(): FakeMfaStore = FakeMfaStore()
    @Bean fun mails(): Mails = Mails()
    @Bean fun events(): Events = Events()
    @Bean fun fakeSessions(): FakeSessions = FakeSessions()
    @Bean fun fakeSessionInspector(): FakeSessionInspector = FakeSessionInspector()
    @Bean fun audits(): Audits = Audits()
    @Bean fun time(): FrozenTime = FrozenTime()
    @Bean fun accountTaskRunner(): AccountTaskRunner = AccountTaskRunner.DIRECT
}
