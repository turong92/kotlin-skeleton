package dev.sumin.skeleton.account.mail

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.account.AccountProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.sumin.skeleton.notification.mail.SmtpMailSender
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSenderImpl

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

    // ---- the code and the link never reach a log line through the REAL mailer path, for every mail kind that carries one

    private val code = "739518"
    private val secrets = listOf(code, token, "reset-password", "magic-link")
    private val carriers = mapOf(
        MailKind.VERIFY_CODE to AccountMail(MailKind.VERIFY_CODE, "victim.person@example.com", "ko", vars = mapOf("code" to code, "minutes" to "10")),
        MailKind.EMAIL_CHANGE_CODE to AccountMail(MailKind.EMAIL_CHANGE_CODE, "victim.person@example.com", "en", vars = mapOf("code" to code, "minutes" to "30")),
        MailKind.REAUTH_CODE to AccountMail(MailKind.REAUTH_CODE, "victim.person@example.com", "ko", vars = mapOf("code" to code, "minutes" to "30")),
        MailKind.DELETE_CODE to AccountMail(MailKind.DELETE_CODE, "victim.person@example.com", "en", vars = mapOf("code" to code, "minutes" to "30")),
        MailKind.PASSWORD_RESET to AccountMail(MailKind.PASSWORD_RESET, "victim.person@example.com", "ko", link, mapOf("minutes" to "30")),
        MailKind.MAGIC_LINK to AccountMail(MailKind.MAGIC_LINK, "victim.person@example.com", "en", "https://app.example.com/magic-link?token=$token", mapOf("minutes" to "15")),
    )

    private fun logged(transport: (AccountProperties.Mail) -> AccountMailTransport, mail: AccountMail): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val old = root.level
        root.addAppender(appender); root.level = Level.TRACE
        try {
            val props = AccountProperties.Mail(linkBaseUrl = "https://app.example.com")
            TemplatedAccountMailer(DefaultAccountMailTemplates(props), transport(props), props).send(mail)
        } finally {
            root.detachAppender(appender); root.level = old
        }
        return appender.list.flatMap { listOfNotNull(it.formattedMessage, it.throwableProxy?.message) }
    }

    @Test
    fun `no mail kind that carries a code or a link leaves it in a log line - protected-env transport (links hidden)`() {
        MailKind.entries.filter { it in carriers }.forEach { kind ->
            val lines = logged({ LogOnlyMailTransport(showLinks = false) }, carriers.getValue(kind))
            assertTrue(lines.isNotEmpty(), "$kind must log something")
            secrets.forEach { secret -> assertTrue(lines.none { secret in it }, "$kind leaked '$secret' into: $lines") }
        }
    }

    @Test
    fun `no mail kind leaves the code or link in a log line when the SMTP sender FAILS (it logs the subject)`() {
        val failing = object : JavaMailSenderImpl() {
            override fun doSend(mimeMessages: Array<out jakarta.mail.internet.MimeMessage>, originalMessages: Array<out Any>?) { throw IllegalStateException("smtp down") }
        }
        carriers.forEach { (kind, mail) ->
            val lines = logged({ MailSenderTransport(SmtpMailSender(failing, "no-reply@example.com")) }, mail)
            assertTrue(lines.any { "Mail send failed" in it }, "$kind: the failure path must have run: $lines")
            secrets.forEach { secret -> assertTrue(lines.none { secret in it }, "$kind leaked '$secret' into: $lines") }
        }
    }

    @Test
    fun `no template, ko or en, puts a code or a link in the SUBJECT`() {
        val templates = DefaultAccountMailTemplates(AccountProperties.Mail())
        for (kind in MailKind.entries) for (lang in listOf("ko", "en")) {
            val subject = templates.render(kind, lang, mapOf("code" to code, "minutes" to "10", "days" to "30", "method" to "google"), link).subject
            assertTrue(code !in subject && token !in subject && "https://" !in subject, "$kind/$lang subject carries a secret: $subject")
        }
    }

    @Test
    fun `the html part carries the code or link yet reaches no log line - hidden-links transport and failing SMTP`() {
        var htmlSeen: String? = null
        MailKind.entries.filter { it in carriers }.forEach { kind ->
            val recording = { p: AccountProperties.Mail -> AccountMailTransport { to, subject, text, html -> htmlSeen = html; LogOnlyMailTransport(false).send(to, subject, text, html) } }
            val lines = logged(recording, carriers.getValue(kind))
            val html = requireNotNull(htmlSeen) { "$kind: the HTML part must exist for this test to mean anything" }
            assertTrue(secrets.any { it in html.replace("&amp;", "&") }, "$kind: html carries its code or link")
            secrets.forEach { secret -> assertTrue(lines.none { secret in it }, "$kind leaked '$secret' (html part) into: $lines") }
            assertTrue(lines.none { "<html" in it || "<table" in it }, "$kind: the html body itself is never logged")
        }
        val failing = object : JavaMailSenderImpl() {
            override fun doSend(mimeMessages: Array<out jakarta.mail.internet.MimeMessage>, originalMessages: Array<out Any>?) { throw IllegalStateException("smtp down") }
        }
        carriers.forEach { (kind, mail) ->
            val lines = logged({ MailSenderTransport(SmtpMailSender(failing, "no-reply@example.com")) }, mail)
            assertTrue(lines.none { "<html" in it || "<table" in it }, "$kind: failure path logged the html: $lines")
        }
    }
}
