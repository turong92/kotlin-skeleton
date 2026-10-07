package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B1 — 이미 계정이 있는 주소로 가입을 요청하면 나가는 메일이 **다음 걸음**을 준다: 로그인 페이지 링크 · 어떤 수단으로 가입했는지 · 비밀번호 재설정 링크 ·
 * (`auth-magic-link` 가 있으면) 1회용 매직 링크. 화면 응답은 그대로 202 이고, 메일 예산 · 재설정 한도는 기존 것을 그대로 쓴다.
 */
class AlreadyRegisteredMailTest {
    private val pw = "tangerine-42-moon"

    private fun harness(magic: MagicLinkIssuer? = null, props: AccountProperties = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        verification = AccountProperties.Verification(perEmail = 50, signUpAttemptsPerEmail = 50, guessesPerEmail = 500),
    )) = AccountHarness(props, magicLinks = magic)

    private fun AccountHarness.again(email: String = "ann@example.com") = signUp(email, pw).also { assertEquals(SignUpStatus.VERIFICATION_SENT, it.status) }
    private fun AccountHarness.alreadyMail() = mailer.of(MailKind.ALREADY_REGISTERED).last()

    @Test
    fun `a password account is told its sign-in method and gets the login page and a working reset link`() {
        val h = harness()
        val a = h.activeAccount()
        h.mailer.sent.clear()
        h.again()
        val mail = h.alreadyMail()
        assertEquals("https://app.example.com/login", mail.vars["loginUrl"])
        assertEquals("password", mail.vars["methods"])
        val reset = mail.vars.getValue("resetUrl")
        assertTrue(reset.startsWith("https://app.example.com/reset-password?token="), reset)
        assertEquals("30", mail.vars["resetMinutes"])
        val grant = assertNotNull(h.tokens.peek(TokenPurposes.PASSWORD_RESET, reset.substringAfter("token=")), "the link carries a real reset token")
        assertEquals(a.id, grant.accountId)
        assertNull(mail.vars["magicUrl"], "no magic-link module: that line is absent")
        assertTrue(AccountEventType.PASSWORD_RESET_REQUESTED in h.events.types())
    }

    @Test
    fun `a social-only account is told its provider and may still set a password through the reset link`() {
        val h = harness()
        val now = h.time.now()
        h.repo.insert(
            Account("acc_g", "gina@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, "ko", null, now, now),
            listOf(Identity("idn_g", "acc_g", "google", "g-gina", true, createdAt = now)),
        )
        h.again("gina@example.com")
        val mail = h.alreadyMail()
        assertEquals("google", mail.vars["methods"])
        assertNotNull(mail.vars["resetUrl"])
        assertEquals("ko", mail.locale)
    }

    @Test
    fun `an account with several methods lists them all`() {
        val h = harness()
        val a = h.activeAccount()
        h.repo.addIdentity(Identity(h.core.newIdentityId(), a.id, "kakao", "k-1", true, createdAt = h.time.now()))
        h.mailer.sent.clear()
        h.again()
        assertEquals(setOf("password", "kakao"), h.alreadyMail().vars.getValue("methods").split(",").toSet())
    }

    @Test
    fun `with the magic-link module present the mail carries a one-time sign-in link, without it the line is simply absent`() {
        val issued = mutableListOf<String>()
        val h = harness(magic = MagicLinkIssuer { account -> issued += account.id; IssuedLink("https://app.example.com/magic-link?token=abcdefghijklmnopqrstuv", 15) })
        val a = h.activeAccount()
        h.mailer.sent.clear()
        h.again()
        val mail = h.alreadyMail()
        assertEquals("https://app.example.com/magic-link?token=abcdefghijklmnopqrstuv", mail.vars["magicUrl"])
        assertEquals("15", mail.vars["magicMinutes"])
        assertEquals(listOf(a.id), issued)
    }

    @Test
    fun `an issuer that has no link to give (its own budget is spent) leaves the line out and the mail still goes`() {
        val h = harness(magic = MagicLinkIssuer { null })
        h.activeAccount()
        h.mailer.sent.clear()
        h.again()
        assertNull(h.alreadyMail().vars["magicUrl"])
        assertNotNull(h.alreadyMail().vars["loginUrl"])
    }

    @Test
    fun `the reset budget is the forgot-password budget - once it is spent the mail has no reset link, and the mail itself spends it`() {
        val h = harness()
        h.activeAccount()
        repeat(3) { h.passwords.forgot("ann@example.com", "203.0.113.${it + 1}", null) }    // reset.perEmail = 3 per hour
        h.mailer.sent.clear()
        h.again()
        val mail = h.alreadyMail()
        assertNull(mail.vars["resetUrl"], "the shared reset budget is used up")
        assertNotNull(mail.vars["loginUrl"])

        val fresh = harness()
        fresh.activeAccount()
        fresh.mailer.sent.clear()
        repeat(3) { fresh.again() }                                                         // three already-registered mails take the three slots
        assertEquals(3, fresh.mailer.of(MailKind.ALREADY_REGISTERED).count { it.vars["resetUrl"] != null })
        fresh.mailer.sent.clear()
        fresh.passwords.forgot("ann@example.com", "203.0.113.9", null)
        assertEquals(0, fresh.mailer.of(MailKind.PASSWORD_RESET).size, "the forgot request finds the budget spent by the mails")
    }

    @Test
    fun `a suspended account gets neither a reset nor a magic link - it is frozen - but still the login page and its methods`() {
        val h = harness(magic = MagicLinkIssuer { IssuedLink("https://x/magic?token=abcdefghijklmnopqrstuv", 15) })
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount()
        h.admin.suspend(admin.id, a.id, "abuse")
        h.mailer.sent.clear()
        h.again()
        val mail = h.alreadyMail()
        assertNull(mail.vars["resetUrl"]); assertNull(mail.vars["magicUrl"])
        assertEquals("password", mail.vars["methods"])
    }

    @Test
    fun `the mail budget per address still caps the mails, and a deleted account gets no mail at all`() {
        val h = AccountHarness()   // verification.perEmail = 3
        h.activeAccount()
        h.mailer.sent.clear()
        repeat(5) { h.signUp("ann@example.com", pw) }
        // three mails of either kind per address and window - one slot went to the code mail that created the account
        assertEquals(2, h.mailer.sent.count { it.kind == MailKind.ALREADY_REGISTERED || it.kind == MailKind.VERIFY_CODE })

        val gone = harness()
        val b = gone.activeAccount("bob@example.com")
        gone.deletion.delete(b.id, ReauthInput(pw), null)
        gone.mailer.sent.clear()
        gone.again("bob@example.com")
        assertEquals(0, gone.mailer.of(MailKind.ALREADY_REGISTERED).size)
    }

    @Test
    fun `the answer to the sign-up request is the same 202 shape as for a free address`() {
        val h = harness()
        h.activeAccount()
        val taken = h.signUp("ann@example.com", pw)
        val free = h.signUp("free@example.com", pw)
        assertEquals(free.status, taken.status)
        assertEquals(free.signUpId!!.length, taken.signUpId!!.length)
        assertEquals(Duration.between(h.time.now(), free.expiresAt), Duration.between(h.time.now(), taken.expiresAt), "same countdown values")
        assertEquals(free.resendAvailableAt, taken.resendAvailableAt)
    }
}
