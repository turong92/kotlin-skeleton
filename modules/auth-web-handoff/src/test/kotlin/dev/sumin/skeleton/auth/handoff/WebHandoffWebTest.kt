package dev.sumin.skeleton.auth.handoff

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.account.WebHandoffIssuer
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.account.token.InMemoryOneTimeTokenStore
import dev.sumin.skeleton.account.token.OneTimeTokenStore
import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventListener
import dev.sumin.skeleton.auth.session.SessionEventType
import dev.sumin.skeleton.handofftest.HandoffTestApplication
import dev.sumin.skeleton.handofftest.FakeSessionInspector
import dev.sumin.skeleton.handofftest.HandoffTestBeans
import dev.sumin.skeleton.handofftest.MutableTime
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [HandoffTestApplication::class],
    properties = [
        "skeleton.auth-web-handoff.enabled=true",
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
    ],
)
@AutoConfigureMockMvc
@Import(HandoffTestBeans::class)
class WebHandoffWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var issuer: WebHandoffIssuer
    @Autowired lateinit var signIn: AccountSignInService
    @Autowired lateinit var store: OneTimeTokenStore
    @Autowired lateinit var sessions: FakeSessionInspector
    @Autowired lateinit var listeners: List<SessionEventListener>

    private fun account(): String =
        signIn.signIn(SignInProof("handoff_test", "h${System.nanoTime()}@example.com", "h${System.nanoTime()}@example.com", emailVerified = true, allowSignUp = true))!!.accountId

    private fun issueFor(accountId: String): String = issuer.issue(accountId, sessions.open(accountId))

    private fun redeem(code: String) =
        mvc.perform(post("/api/v1/auth/web-handoff/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"code":"$code"}"""))

    @Test
    fun `a code signs in exactly the account it was issued for, and only once`() {
        val a = account()
        val b = account()
        val codeA = issueFor(a)
        val codeB = issueFor(b)
        val ra = redeem(codeA).andExpect(status().isOk).andReturn()
        val rb = redeem(codeB).andExpect(status().isOk).andReturn()
        assertEquals(a, JsonPath.read<String>(ra.response.contentAsString, "$.value.principal.accountId"))
        assertEquals(b, JsonPath.read<String>(rb.response.contentAsString, "$.value.principal.accountId"))
        redeem(codeA).andExpect(status().isGone)
    }

    @Test
    fun `an expired code is dead`() {
        val code = issueFor(account())
        time.advance(Duration.ofMinutes(3))
        redeem(code).andExpect(status().isGone)
    }

    @Test
    fun `sixteen simultaneous exchanges of one code produce one session`() {
        val code = issueFor(account())
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val futures = (1..16).map { pool.submit<Int> { go.await(); redeem(code).andReturn().response.status } }
        go.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()
        assertEquals(1, results.count { it == 200 }, results.toString())
        assertTrue(results.all { it == 200 || it == 410 })
    }

    @Test
    fun `issuing a new code for the account closes the one still open`() {
        val id = account()
        val old = issueFor(id)
        val fresh = issueFor(id)
        redeem(old).andExpect(status().isGone)
        redeem(fresh).andExpect(status().isOk)
    }

    @Test
    fun `closing every session of the account (logout everywhere, password reset, deletion) closes the open code`() {
        val id = account()
        val other = account()
        val code = issueFor(id)
        val untouched = issueFor(other)
        listeners.forEach { it.on(SessionEvent(SessionEventType.SESSIONS_REVOKED, id, count = 2, reason = "USER_REVOKED_ALL")) }
        redeem(code).andExpect(status().isGone)
        redeem(untouched).andExpect(status().isOk)   // another account's code is not touched
    }

    @Test
    fun `only the hash is stored, and the code never reaches a log line or an error body`() {
        val logs = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(logs)
        try {
            val used = issueFor(account())
            val bad = "x".repeat(40)
            redeem(used).andExpect(status().isOk)
            val replay = redeem(used).andExpect(status().isGone).andReturn().response.contentAsString
            val garbage = redeem(bad).andExpect(status().isGone).andReturn().response.contentAsString
            assertFalse(replay.contains(used) || garbage.contains(bad))
            assertFalse(store.let { (it as InMemoryOneTimeTokenStore).hashes() }.contains(used))
            assertFalse(logs.list.any { used in it.formattedMessage || bad in it.formattedMessage }, "the code must not be logged")
            assertEquals("WebHandoffRedeemRequest(code=<redacted>)", WebHandoffRedeemRequest(used).toString())
        } finally {
            root.detachAppender(logs)
        }
    }

    @Test
    fun `a session that is already closed cannot get a code`() {
        val id = account()
        val sid = sessions.open(id)
        sessions.open.remove(sid)
        assertFailsWith<ApplicationException> { issuer.issue(id, sid) }
        assertFailsWith<ApplicationException> { issuer.issue(id, null) }
        assertFailsWith<ApplicationException> { issuer.issue(id, sessions.open(account())) }   // another account's session
    }

    @Test
    fun `a code dies when the session that asked for it is closed before the exchange`() {
        val id = account()
        val sid = sessions.open(id)
        val code = issuer.issue(id, sid)
        sessions.open.remove(sid)   // revoked, and no SESSIONS_REVOKED event reaches the listener (zero other sessions)
        redeem(code).andExpect(status().isGone)
    }
}
