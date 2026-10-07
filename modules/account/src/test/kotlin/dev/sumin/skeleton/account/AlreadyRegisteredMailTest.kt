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
 * B1 — 이미 계정이 있는 주소로 가입을 요청하면 나가는 메일이 **다음 걸음**을 준다: 로그인 페이지 링크 · 어떤 수단으로 가입했는지 · "비밀번호를 잊었다면" 재설정 **요청 페이지** 링크.
 * 기본은 **토큰 없는 링크만**이다 — 남이 넣은 가입 요청이 주인의 재설정 링크를 무효화하거나 `reset:email` · `magic-link:email` 한도를 바닥내지 못하게 (I-4).
 * `sign-up.existing-account-mail.include-credentials-links=true` 면 (최초 가입 요청에서만, 열린 토큰이 없을 때만) 재설정 링크와 (`auth-magic-link` 가 있으면) 1회용 매직 링크도 싣는다.
 * 화면 응답은 늘 같은 202 이다.
 */
class AlreadyRegisteredMailTest {
    private val pw = "tangerine-42-moon"

    private fun harness(magic: MagicLinkIssuer? = null, links: Boolean = false, props: AccountProperties = AccountProperties(
        mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        verification = AccountProperties.Verification(perEmail = 50, signUpAttemptsPerEmail = 50, guessesPerEmail = 500),
        signUp = AccountProperties.SignUp(existingAccountMail = AccountProperties.SignUp.ExistingAccountMail(includeCredentialsLinks = links)),
    )) = AccountHarness(props, magicLinks = magic)

    private fun AccountHarness.again(email: String = "ann@example.com") = signUp(email, pw).also { assertEquals(SignUpStatus.VERIFICATION_SENT, it.status) }
    private fun AccountHarness.alreadyMail() = mailer.of(MailKind.ALREADY_REGISTERED).last()

    @Test
    fun `by default the mail has the login page, the methods and the forgot-password PAGE - no token of any kind is issued`() {
        val h = harness(magic = MagicLinkIssuer { error("the magic-link issuer must not be asked by default") })
        h.activeAccount()
        h.mailer.sent.clear()
        h.events.all.clear()
        h.again()
        val mail = h.alreadyMail()
        assertEquals("https://app.example.com/login", mail.vars["loginUrl"])
        assertEquals("https://app.example.com/forgot-password", mail.vars["forgotUrl"], "the page that REQUESTS a reset, no token")
        assertEquals("password", mail.vars["methods"])
        assertNull(mail.vars["resetUrl"]); assertNull(mail.vars["magicUrl"])
        assertTrue(mail.vars.values.none { "token=" in it }, "no variable carries a token: ${mail.vars}")
        assertEquals(emptyList(), h.tokenStore.hashes(), "nothing was written to the token table")
        assertTrue(AccountEventType.PASSWORD_RESET_REQUESTED !in h.events.types())
    }

    @Test
    fun `a stranger's sign-up requests neither close the owner's reset link nor spend the reset budget`() {
        val h = harness()
        h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.50", null)
        val ownerLink = h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).last())
        repeat(5) { h.again() }
        assertNotNull(h.tokens.peek(TokenPurposes.PASSWORD_RESET, ownerLink), "the owner's link is still open")
        // the owner can ask twice more (reset.per-email = 3 per hour, one used above)
        h.mailer.sent.clear()
        repeat(2) { h.passwords.forgot("ann@example.com", "203.0.113.${60 + it}", null) }
        assertEquals(2, h.mailer.of(MailKind.PASSWORD_RESET).size)
    }

    @Test
    fun `a custom forgot-path is used, and without a link-base-url there is no page link at all`() {
        val h = harness(props = AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com/", forgotPath = "/account/recover"), password = AccountProperties.Password(bcryptStrength = 4),
            verification = AccountProperties.Verification(perEmail = 50, signUpAttemptsPerEmail = 50, guessesPerEmail = 500),
        ))
        h.activeAccount(); h.mailer.sent.clear(); h.again()
        assertEquals("https://app.example.com/account/recover", h.alreadyMail().vars["forgotUrl"])
        val bare = harness(props = AccountProperties(password = AccountProperties.Password(bcryptStrength = 4), verification = AccountProperties.Verification(perEmail = 50, signUpAttemptsPerEmail = 50, guessesPerEmail = 500)))
        bare.activeAccount(); bare.mailer.sent.clear(); bare.again()
        assertNull(bare.alreadyMail().vars["forgotUrl"]); assertNull(bare.alreadyMail().vars["loginUrl"])
    }

    @Test
    fun `with include-credentials-links the FIRST request carries a working reset link, and a social-only account may still set a password through it`() {
        val h = harness(links = true)
        val a = h.activeAccount()
        h.mailer.sent.clear()
        h.again()
        val mail = h.alreadyMail()
        val reset = mail.vars.getValue("resetUrl")
        assertTrue(reset.startsWith("https://app.example.com/reset-password?token="), reset)
        assertEquals("30", mail.vars["resetMinutes"])
        assertEquals(a.id, assertNotNull(h.tokens.peek(TokenPurposes.PASSWORD_RESET, reset.substringAfter("token="))).accountId)
        assertTrue(AccountEventType.PASSWORD_RESET_REQUESTED in h.events.types())
        assertNull(mail.vars["forgotUrl"], "the reset link replaces the forgot-page line")
        assertEquals("https://app.example.com/login", mail.vars["loginUrl"])

        val social = harness(links = true)
        val now = social.time.now()
        social.repo.insert(Account("acc_g", "gina@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, "ko", null, now, now), listOf(Identity("idn_g", "acc_g", "google", "g-gina", true, createdAt = now)))
        social.again("gina@example.com")
        assertEquals("google", social.alreadyMail().vars["methods"])
        assertNotNull(social.alreadyMail().vars["resetUrl"])
        assertEquals("ko", social.alreadyMail().locale)
    }

    @Test
    fun `with include-credentials-links an OPEN reset token is neither replaced nor closed - the link is left out and no budget is spent`() {
        val h = harness(links = true)
        h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.50", null)
        val ownerLink = h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).last())
        h.mailer.sent.clear()
        repeat(3) { h.again() }
        h.mailer.of(MailKind.ALREADY_REGISTERED).forEach { assertNull(it.vars["resetUrl"], "an open token exists: no second one") ; assertNotNull(it.vars["forgotUrl"], "the page link stands in") }
        assertNotNull(h.tokens.peek(TokenPurposes.PASSWORD_RESET, ownerLink), "the owner's link survived all three requests")
    }

    @Test
    fun `with include-credentials-links three requests of a stranger cost the reset budget ONE slot, not three`() {
        val h = harness(links = true)
        h.activeAccount()
        h.mailer.sent.clear()
        repeat(3) { h.again() }
        assertEquals(1, h.mailer.of(MailKind.ALREADY_REGISTERED).count { it.vars["resetUrl"] != null })
        h.mailer.sent.clear()
        repeat(2) { h.passwords.forgot("ann@example.com", "203.0.113.${70 + it}", null) }
        assertEquals(2, h.mailer.of(MailKind.PASSWORD_RESET).size, "the owner still has two of the three hourly slots")
    }

    @Test
    fun `the RESEND path never issues a credential link, even with include-credentials-links and nothing open`() {
        val h = harness(links = true)
        h.activeAccount()
        h.again()   // the first request issued the single link
        h.tokens.invalidate(TokenPurposes.PASSWORD_RESET, "ann@example.com")   // as if it had been used or closed: nothing is open any more
        h.mailer.sent.clear()
        h.time.advance(Duration.ofMinutes(1))
        h.registration.resendVerification(h.signUpIds.getValue("ann@example.com"), "203.0.113.5", null)
        assertEquals(1, h.mailer.of(MailKind.ALREADY_REGISTERED).size)
        assertNull(h.alreadyMail().vars["resetUrl"], "a resend is no new request: no token")
        assertNotNull(h.alreadyMail().vars["forgotUrl"])
        assertNull(h.tokenStore.findOpen(TokenPurposes.PASSWORD_RESET, "ann@example.com", h.time.now()))
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
    fun `with include-credentials-links and the magic-link module the mail carries a one-time sign-in link, without the module the line is simply absent`() {
        val issued = mutableListOf<String>()
        val h = harness(links = true, magic = MagicLinkIssuer { account -> issued += account.id; IssuedLink("https://app.example.com/magic-link?token=abcdefghijklmnopqrstuv", 15) })
        val a = h.activeAccount()
        h.mailer.sent.clear()
        h.again()
        val mail = h.alreadyMail()
        assertEquals("https://app.example.com/magic-link?token=abcdefghijklmnopqrstuv", mail.vars["magicUrl"])
        assertEquals("15", mail.vars["magicMinutes"])
        assertEquals(listOf(a.id), issued)
        val none = harness(links = true, magic = MagicLinkIssuer { null })
        none.activeAccount(); none.mailer.sent.clear(); none.again()
        assertNull(none.alreadyMail().vars["magicUrl"], "an issuer with nothing to give leaves the line out and the mail still goes")
        assertNotNull(none.alreadyMail().vars["loginUrl"])
    }

    @Test
    fun `a suspended account gets no credential link even when they are on - it is frozen - but still the login page and its methods`() {
        val h = harness(links = true, magic = MagicLinkIssuer { IssuedLink("https://x/magic?token=abcdefghijklmnopqrstuv", 15) })
        val admin = h.activeAccount("admin@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val a = h.activeAccount()
        h.admin.suspend(admin.id, a.id, "abuse")
        h.mailer.sent.clear()
        h.again()
        val mail = h.alreadyMail()
        assertNull(mail.vars["resetUrl"]); assertNull(mail.vars["magicUrl"])
        assertEquals("password", mail.vars["methods"])
        assertNotNull(mail.vars["loginUrl"])
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
