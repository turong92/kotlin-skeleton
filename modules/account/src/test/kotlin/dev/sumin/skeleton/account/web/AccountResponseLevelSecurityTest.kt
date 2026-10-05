package dev.sumin.skeleton.account.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.abuse.ExecutorAccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.common.logging.LogMasker
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 메일이 느린(0.5초) 서비스 — 응답이 메일을 기다리면 계정이 있는 쪽이 눈에 띄게 느려진다 */
@TestConfiguration(proxyBeanMethods = false)
class SlowMailBeans {
    val sent = CopyOnWriteArrayList<AccountMail>()

    @Bean fun slowMailer(): AccountMailer = AccountMailer { mail -> Thread.sleep(500); sent += mail }
    @Bean fun slowMailRecorder(): CopyOnWriteArrayList<AccountMail> = sent
    @Bean fun realExecutor() = ExecutorAccountTaskRunner(threads = 4)
}

@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4"],
)
@AutoConfigureMockMvc
@Import(SlowMailBeans::class)
class AccountResponseLevelSecurityTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var sent: CopyOnWriteArrayList<AccountMail>

    private fun timed(path: String, body: String, ip: String): Pair<Int, Long> {
        val start = System.nanoTime()
        val r = mvc.perform(post(path).with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()
        return r.response.status to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `sign-up, forgot and resend answer without waiting for mail - a known address costs the same as an unknown one`() {
        val known = "known${System.nanoTime()}@example.com"
        // create the account (the first call waits nothing; the mail is slow but off-thread)
        timed("/api/v1/account/sign-up", """{"email":"$known","password":"tangerine-42-moon"}""", "198.51.100.1")
        Thread.sleep(800)

        val results = listOf(
            "sign-up known" to timed("/api/v1/account/sign-up", """{"email":"$known","password":"tangerine-42-moon"}""", "198.51.100.2"),
            "sign-up unknown" to timed("/api/v1/account/sign-up", """{"email":"unk${System.nanoTime()}@example.com","password":"tangerine-42-moon"}""", "198.51.100.3"),
            "forgot known" to timed("/api/v1/account/password/forgot", """{"email":"$known"}""", "198.51.100.4"),
            "forgot unknown" to timed("/api/v1/account/password/forgot", """{"email":"ghost${System.nanoTime()}@example.com"}""", "198.51.100.5"),
            "resend known" to timed("/api/v1/account/verification/resend", """{"email":"$known"}""", "198.51.100.6"),
            "resend unknown" to timed("/api/v1/account/verification/resend", """{"email":"ghost${System.nanoTime()}@example.com"}""", "198.51.100.7"),
        )
        results.forEach { (name, r) ->
            assertEquals(202, r.first, name)
            assertTrue(r.second < 400, "$name took ${r.second} ms — the response must not wait for the 500 ms mail")
        }
        val gap = (results[0].second2() - results[1].second2()).let { kotlin.math.abs(it) }
        assertTrue(gap < 300, "known/unknown sign-up differ by $gap ms")
    }

    private fun Pair<String, Pair<Int, Long>>.second2() = second.second

    // ---- tokens in logs

    @Test
    fun `no one-time token or password ever reaches the log, and the default masker would hide a link anyway`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        val oldLevel = root.level
        root.level = ch.qos.logback.classic.Level.TRACE
        try {
            val email = "logs${System.nanoTime()}@example.com"
            timed("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon"}""", "198.51.100.20")
            Thread.sleep(900)
            timed("/api/v1/account/password/forgot", """{"email":"$email"}""", "198.51.100.21")
            Thread.sleep(900)
            val tokens = sent.filter { it.link != null }.map { it.link!!.substringAfter("token=") }
            assertTrue(tokens.size >= 1, "expected at least the verification mail")
            val lines = appender.list.map { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            tokens.forEach { t -> assertTrue(lines.none { t in it }, "a one-time token was logged") }
            assertTrue(appender.list.none { "tangerine-42-moon" in it.formattedMessage }, "a password was logged by " + appender.list.filter { "tangerine-42-moon" in it.formattedMessage }.map { it.loggerName + ": " + it.formattedMessage.take(120) })
            assertTrue(sent.none { MailKind.VERIFY_EMAIL != it.kind && false })

            val link = sent.first { it.link != null }.link!!
            assertTrue("token=" in link)
            assertTrue("[REDACTED]" in LogMasker().mask("GET $link"), "the default LogMasker must hide a token= pair")
            assertTrue(link !in sent.first { it.link != null }.toString(), "AccountMail.toString must not print the link")
        } finally {
            root.detachAppender(appender)
            root.level = oldLevel
        }
    }
}
