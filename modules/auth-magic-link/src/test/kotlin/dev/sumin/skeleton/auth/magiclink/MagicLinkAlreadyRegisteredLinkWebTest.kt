package dev.sumin.skeleton.auth.magiclink

import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.magiclinktest.MagicLinkTestApplication
import dev.sumin.skeleton.magiclinktest.MagicLinkTestBeans
import dev.sumin.skeleton.magiclinktest.Mails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private val nextIp = java.util.concurrent.atomic.AtomicInteger()

/** `sign-up.existing-account-mail.include-credentials-links=true` — the opt-in: the already-registered mail carries a one-time sign-in link, but never replaces one that is open (I-4) */
@SpringBootTest(
    classes = [MagicLinkTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-magic-link.sign-up=true",
        "skeleton.account.sign-up.existing-account-mail.include-credentials-links=true",
    ],
)
@AutoConfigureMockMvc
@Import(MagicLinkTestBeans::class)
class MagicLinkAlreadyRegisteredLinkWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var tokens: OneTimeTokens

    @BeforeEach fun clear() { mails.sent.clear() }

    private fun ip() = "198.51.100.${100 + nextIp.getAndIncrement() % 100}"
    private fun request(email: String) = mvc.perform(post("/api/v1/auth/magic-link/request").with { it.remoteAddr = ip(); it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"}"""))
    private fun redeem(token: String) = mvc.perform(post("/api/v1/auth/magic-link/redeem").contentType(MediaType.APPLICATION_JSON).content("""{"token":"$token"}"""))
    private fun signUp(address: String) = mvc.perform(
        post("/api/v1/account/sign-up").with { it.remoteAddr = ip(); it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$address","password":"tangerine-42-moon"}"""),
    ).andExpect(status().isAccepted)
    private fun unique() = "ml${System.nanoTime()}@example.com"

    @Test
    fun `the first request carries a one-time sign-in link that really signs in`() {
        val email = unique()
        request(email)
        redeem(mails.tokenOf(mails.sent.last { it.kind == MailKind.MAGIC_LINK })).andExpect(status().isOk)
        mails.sent.clear()
        signUp(email)
        val mail = mails.sent.last { it.kind == MailKind.ALREADY_REGISTERED }
        assertEquals("15", mail.vars["magicMinutes"])
        val link = mail.vars.getValue("magicUrl")
        assertTrue(link.startsWith("https://app.example.com/magic-link?token="), link)
        redeem(link.substringAfter("token=")).andExpect(status().isOk).andExpect(jsonPath("$.value.principal.email").value(email))
    }

    @Test
    fun `an open sign-in link is never replaced - the second request has no magic line and the first link still works, and the budget cost one slot`() {
        val email = unique()
        request(email)
        redeem(mails.tokenOf(mails.sent.last { it.kind == MailKind.MAGIC_LINK })).andExpect(status().isOk)
        mails.sent.clear()
        repeat(3) { signUp(email) }
        val withLink = mails.sent.filter { it.kind == MailKind.ALREADY_REGISTERED }.filter { it.vars["magicUrl"] != null }
        assertEquals(1, withLink.size, "only the first request issued a link")
        val first = withLink.single().vars.getValue("magicUrl").substringAfter("token=")
        assertNotNull(tokens.peek(TokenPurposes.MAGIC_LINK, first), "later requests did not close it")
        mails.sent.filter { it.kind == MailKind.ALREADY_REGISTERED }.filter { it.vars["magicUrl"] == null }.forEach { assertNull(it.vars["magicUrl"]) }
        // the owner's own link request: per-email magic-link budget (default 3/h) had only one slot taken by the requests above (+1 for the first request earlier)
        mails.sent.clear()
        request(email).andExpect(status().isAccepted)
        assertEquals(1, mails.sent.count { it.kind == MailKind.MAGIC_LINK }, "the owner can still ask for a link")
    }
}
