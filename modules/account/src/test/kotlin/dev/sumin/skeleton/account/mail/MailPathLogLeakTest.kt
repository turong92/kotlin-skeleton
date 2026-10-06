package dev.sumin.skeleton.account.mail

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.account.AccountProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/** 위협 표 "비밀번호 · 토큰이 로그에" — 실제 `TemplatedAccountMailer` + `LogOnlyMailTransport` 길로 보낸 메일이 로그에 링크 · 토큰 · 주소를 남기지 않는다 */
class MailPathLogLeakTest {
    private val token = "Zq9-secret-token-0123456789abcdefgh"
    private val link = "https://app.example.com/reset-password?token=$token"

    private fun capture(showLinks: Boolean): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val old = root.level
        root.addAppender(appender); root.level = Level.TRACE
        try {
            val props = AccountProperties.Mail(linkBaseUrl = "https://app.example.com")
            val mailer = TemplatedAccountMailer(DefaultAccountMailTemplates(props), LogOnlyMailTransport(showLinks), props)
            val mail = AccountMail(MailKind.PASSWORD_RESET, "victim.person@example.com", "ko", link, mapOf("minutes" to "30"))
            mailer.send(mail)
            org.slf4j.LoggerFactory.getLogger("test").debug("mail object: {}", mail)
        } finally {
            root.detachAppender(appender); root.level = old
        }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `with links hidden (the protected-env default) neither token nor link nor the whole address reaches the log`() {
        val lines = capture(showLinks = false)
        assertTrue(lines.any { "MAIL (not sent" in it }, "the transport must have logged something: $lines")
        assertTrue(lines.none { token in it || "reset-password" in it }, lines.toString())
        assertTrue(lines.none { "victim.person@example.com" in it }, "the recipient is masked: $lines")
    }

    @Test
    fun `links appear in the log only when explicitly switched on`() {
        assertEquals(true, capture(showLinks = true).any { token in it })
    }
}
