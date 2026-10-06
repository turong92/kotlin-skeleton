package dev.sumin.skeleton.auth.magiclink

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.magiclinktest.MagicLinkTestApplication
import dev.sumin.skeleton.magiclinktest.MagicLinkTestBeans
import dev.sumin.skeleton.magiclinktest.Mails
import dev.sumin.skeleton.magiclinktest.MutableTime
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [MagicLinkTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-magic-link.sign-up=true",
    ],
)
@AutoConfigureMockMvc
@Import(MagicLinkTestBeans::class)
class MagicLinkWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var tokens: OneTimeTokens

    @BeforeEach fun clear() { mails.sent.clear() }

    private fun json(s: String) = MediaType.APPLICATION_JSON to s
    private fun request(email: String, ip: String = "198.51.100.${(1..250).random()}") =
        mvc.perform(post("/api/v1/auth/magic-link/request").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"}"""))
    private fun redeem(token: String) =
        mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$token"}"""))
    private fun unique() = "ml${System.nanoTime()}@example.com"
    private fun lastToken() = mails.tokenOf(mails.sent.last { it.kind == MailKind.MAGIC_LINK })
    private fun body(r: MvcResult) = JsonPath.read<Any>(r.response.contentAsString, "$.value")

    @Test
    fun `request is 202 with the same body for a known and an unknown address and mails a short-lived link`() {
        val email = unique()
        val first = request(email).andExpect(status().isAccepted).andReturn()
        redeem(lastToken()).andExpect(status().isOk)   // creates the account (sign-up is on)
        mails.sent.clear()
        val known = request(email).andExpect(status().isAccepted).andReturn()
        val unknown = request(unique()).andExpect(status().isAccepted).andReturn()
        assertEquals(body(first), body(known))
        assertEquals(body(known), body(unknown))
        val mail = mails.sent.first()
        assertTrue(mail.link!!.startsWith("https://app.example.com/magic-link?token="))
        assertEquals("15", mail.vars["minutes"])
    }

    @Test
    fun `redeeming creates the account on first use, signs in, and the link works once`() {
        val email = unique()
        request(email)
        val token = lastToken()
        redeem(token).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.accessToken").exists())
            .andExpect(jsonPath("$.value.principal.email").value(email))
        redeem(token).andExpect(status().isGone).andExpect(jsonPath("$.code").value("ACCOUNT.TOKEN_INVALID"))
    }

    @Test
    fun `the new account lists magic link as a sign-in method and the email counts as verified`() {
        val email = unique()
        request(email)
        val r = redeem(lastToken()).andExpect(status().isOk).andReturn()
        val auth = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.emailVerified").value(true))
            .andExpect(jsonPath("$.value.hasPassword").value(false))
            .andExpect(jsonPath("$.value.methods[0].method").value("magic_link"))
            .andExpect(jsonPath("$.value.methods[0].subject").value(email))
    }

    @Test
    fun `an expired link is dead`() {
        request(unique())
        val token = lastToken()
        time.advance(Duration.ofMinutes(16))
        redeem(token).andExpect(status().isGone)
    }

    @Test
    fun `a link for another purpose cannot be redeemed here`() {
        val verify = tokens.issue(TokenPurposes.VERIFY_EMAIL, "x@example.com", "acc_x", Duration.ofHours(1))
        redeem(verify).andExpect(status().isGone)
    }

    @Test
    fun `garbage tokens are gone, not server errors`() {
        redeem("nonsense-nonsense-nonsense-nonsense").andExpect(status().isGone)
        mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest)
    }

    @Test
    fun `sixteen simultaneous redemptions of one link produce one session`() {
        request(unique())
        val token = lastToken()
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val codes = (1..16).map { pool.submit<Int> { go.await(); redeem(token).andReturn().response.status } }
        go.countDown()
        val results = codes.map { it.get() }
        pool.shutdown()
        assertEquals(1, results.count { it == 200 }, results.toString())
        assertTrue(results.all { it == 200 || it == 410 })
    }

    @Test
    fun `requests are capped per address silently and per IP loudly`() {
        val email = unique()
        repeat(6) { request(email, "198.51.100.${200 + it}").andExpect(status().isAccepted) }
        assertEquals(3, mails.sent.count { it.kind == MailKind.MAGIC_LINK && it.to == email })
        repeat(10) { request(unique(), "198.51.100.9").andExpect(status().isAccepted) }
        request(unique(), "198.51.100.9").andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("ACCOUNT.RATE_LIMITED"))
    }

    @Test
    fun `a bad address is a validation error, not a mail`() {
        mvc.perform(post("/api/v1/auth/magic-link/request").contentType(MediaType.APPLICATION_JSON).content("""{"email":"nope"}""")).andExpect(status().isBadRequest)
        assertNotNull(mails)
        assertEquals(0, mails.sent.size)
    }
}

@SpringBootTest(
    classes = [MagicLinkTestApplication::class],
    properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4"],
)
@AutoConfigureMockMvc
@Import(MagicLinkTestBeans::class)
class MagicLinkSignUpClosedWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails

    @Test
    fun `with sign-up closed (the default) an unknown address gets the same 202 and no mail, so no account appears`() {
        mails.sent.clear()
        mvc.perform(post("/api/v1/auth/magic-link/request").contentType(MediaType.APPLICATION_JSON).content("""{"email":"stranger@example.com"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("SENT"))
        assertEquals(0, mails.sent.size)
    }

    @Test
    fun `a stranger cannot mint an account by redeeming a link they were never sent`() {
        val tokens = mvc.dispatcherServlet.webApplicationContext!!.getBean(OneTimeTokens::class.java)
        val raw = tokens.issue(TokenPurposes.MAGIC_LINK, "ghost@example.com", null, Duration.ofMinutes(5))
        mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$raw"}""")).andExpect(status().isGone)
    }

    @Test
    fun `with sign-up closed a link still signs an EXISTING account in (the default must not make magic link dead)`() {
        mails.sent.clear()
        val email = "existing${System.nanoTime()}@example.com"
        mvc.perform(post("/api/v1/account/sign-up").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted)
        val verify = mails.tokenOf(mails.sent.last { it.kind == MailKind.VERIFY_EMAIL })
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$verify"}""")).andExpect(status().isOk)
        mails.sent.clear()
        mvc.perform(post("/api/v1/auth/magic-link/request").contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"}""")).andExpect(status().isAccepted)
        val link = mails.tokenOf(mails.sent.last { it.kind == MailKind.MAGIC_LINK })
        mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$link"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.principal.email").value(email))
    }
}
