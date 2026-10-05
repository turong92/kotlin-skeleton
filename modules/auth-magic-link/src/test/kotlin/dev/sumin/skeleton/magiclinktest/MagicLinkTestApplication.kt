package dev.sumin.skeleton.magiclinktest

import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class MagicLinkTestApplication

class MutableTime(@Volatile var at: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = at
    fun advance(d: Duration) { at = at.plus(d) }
}

class Mails : AccountMailer {
    val sent = CopyOnWriteArrayList<AccountMail>()
    override fun send(mail: AccountMail) { sent += mail }
    fun tokenOf(mail: AccountMail) = mail.link!!.substringAfter("token=")
}

@TestConfiguration(proxyBeanMethods = false)
class MagicLinkTestBeans {
    @Bean fun mails(): Mails = Mails()
    @Bean fun time(): MutableTime = MutableTime()
    @Bean fun accountTaskRunner(): AccountTaskRunner = AccountTaskRunner.DIRECT
}
