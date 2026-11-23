package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C1 — 이메일 변경 코드도 **대상 주소**당 한도를 가입과 같은 버킷으로 쓴다: 계정을 몇 개 만들든 한 메일함에 걸리는 추측 · 메일의 총량이 같은 상한 안에 있다.
 */
class EmailChangeAddressBudgetTest {
    private val pw = ReauthInput("tangerine-42-moon")
    private val victim = "victim@example.com"

    private fun harness(v: AccountProperties.Verification = AccountProperties.Verification(), bootstrapAdmin: String = "") = AccountHarness(
        AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
            password = AccountProperties.Password(bcryptStrength = 4),
            verification = v,
            bootstrap = AccountProperties.Bootstrap(bootstrapAdmin),
        ),
    )

    private fun err(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }

    @Test
    fun `many accounts asking to change to one free address cannot mail it more often than the per-address mail budget - and that budget is the sign-up one`() {
        val h = harness()
        val accounts = (1..8).map { h.activeAccount("u$it@example.com") }
        h.mailer.sent.clear()
        accounts.forEachIndexed { i, a -> h.emailChange.request(a.id, victim, pw, "ses_$i") }
        assertEquals(3, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size, "verification.per-email (3) mails per window to one address, not 5 per account")

        h.signUp(victim, ip = "198.51.100.9")
        assertTrue(h.mailer.of(MailKind.VERIFY_CODE).none { it.to == victim }, "the sign-up code mail draws on the same per-address budget")
    }

    @Test
    fun `sign-up attempts and email-change challenges draw on ONE per-address open budget - sign-up first`() {
        val h = harness(AccountProperties.Verification(signUpAttemptsPerEmail = 2, perEmail = 100))
        val a = h.activeAccount()
        repeat(2) { h.signUp(victim, ip = "198.51.100.$it") }
        h.mailer.sent.clear()
        h.emailChange.request(a.id, victim, pw, "ses_1")
        assertEquals(0, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size, "the address's guess budget is spent; no guessable challenge is handed out for it")
    }

    @Test
    fun `sign-up attempts and email-change challenges draw on ONE per-address open budget - email change first`() {
        val h = harness(AccountProperties.Verification(signUpAttemptsPerEmail = 2, perEmail = 100))
        val accounts = (1..3).map { h.activeAccount("u$it@example.com") }
        accounts.take(2).forEachIndexed { i, a -> h.emailChange.request(a.id, victim, pw, "ses_$i") }
        h.mailer.sent.clear()
        val outcome = h.signUp(victim, ip = "198.51.100.50")
        assertEquals(SignUpStatus.VERIFICATION_SENT, outcome.status, "the answer is the same")
        assertNull(h.challengeStore.find(h.challenges.idOf(outcome.signUpId!!)!!), "no attempt is stored once email-change challenges used the address's budget")
        assertEquals(0, h.mailer.of(MailKind.VERIFY_CODE).size)
    }

    @Test
    fun `over budget the visible state and the countdown are identical to an in-budget request`() {
        val h = harness(AccountProperties.Verification(signUpAttemptsPerEmail = 1))
        val first = h.activeAccount("u1@example.com")
        val second = h.activeAccount("u2@example.com")
        h.emailChange.request(first.id, victim, pw, "ses_1")
        h.emailChange.request(second.id, victim, pw, "ses_2")   // over the open budget
        val a = h.profile.me(first.id)
        val b = h.profile.me(second.id)
        assertEquals(victim, b.pendingEmail, "an over-budget request looks pending exactly like any other")
        assertEquals(a.pendingEmail, b.pendingEmail)
        assertNotNull(b.pendingEmailExpiresAt)
        assertEquals(mapOf("attemptsLeft" to 4), err { h.emailChange.confirm(second.id, "ses_2", "123456") }.data, "the same countdown")
        assertEquals(1, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)
    }

    @Test
    fun `an over-budget challenge can never be won - not even by the right-looking code`() {
        val h = harness(AccountProperties.Verification(signUpAttemptsPerEmail = 1))
        val first = h.activeAccount("u1@example.com")
        val second = h.activeAccount("u2@example.com")
        h.emailChange.request(first.id, victim, pw, "ses_1")
        h.emailChange.request(second.id, victim, pw, "ses_2")
        val row = h.challengeStore.findOpen(ChallengePurposes.EMAIL_CHANGE, second.id, h.time.now())!!
        val hasher = dev.sumin.skeleton.account.challenge.CodeHasher(ByteArray(32) { 7 })   // the harness key
        assertTrue((0..999_999).none { hasher.matches(row.codeHash, row.id, "%06d".format(it)) }, "no 6-digit code matches the stored hash")
    }

    @Test
    fun `an email change never fires the first-admin bootstrap`() {
        val h = harness(bootstrapAdmin = "boss@example.com")
        val a = h.activeAccount()
        h.emailChange.request(a.id, "boss@example.com", pw, "ses_1")
        h.emailChange.confirm(a.id, "ses_1", h.mailer.of(MailKind.EMAIL_CHANGE_CODE).last().vars.getValue("code"))
        assertEquals("boss@example.com", h.repo.findById(a.id)!!.email)
        assertFalse("ADMIN" in h.repo.findById(a.id)!!.roles, "only a sign-up verification (or an explicit grant) may mint the first admin")
    }

    @Test
    fun `confirming is limited per IP, in the same bucket as sign-up code entry`() {
        val h = harness(AccountProperties.Verification(attemptsPerIp = 3))
        val a = h.activeAccount()
        h.emailChange.request(a.id, "new@example.com", pw, "ses_1")
        val other = h.signUp("someone@example.com", ip = "198.51.100.7")
        assertFailsWith<ApplicationException> { h.registration.verifyEmail(other.signUpId!!, "000000", "192.0.2.5") }
        assertFailsWith<ApplicationException> { h.registration.verifyEmail(other.signUpId!!, "000001", "192.0.2.5") }
        err { h.emailChange.confirm(a.id, "ses_1", "000000", ip = "192.0.2.5") }
        assertFailsWith<RateLimitedException> { h.emailChange.confirm(a.id, "ses_1", "000001", ip = "192.0.2.5") }
    }

    @Test
    fun `the per-address guess cap counts sign-up guesses and email-change guesses together`() {
        val h = harness(AccountProperties.Verification(guessesPerEmail = 3))
        val a = h.activeAccount()
        h.emailChange.request(a.id, victim, pw, "ses_1")
        val attempt = h.signUp(victim, ip = "198.51.100.8")
        assertEquals(mapOf("attemptsLeft" to 4), err { h.emailChange.confirm(a.id, "ses_1", "000000") }.data)
        assertEquals(mapOf("attemptsLeft" to 4), err { h.registration.verifyEmail(attempt.signUpId!!, "000000", null) }.data)
        assertEquals(mapOf("attemptsLeft" to 3), err { h.emailChange.confirm(a.id, "ses_1", "000001") }.data)
        assertFailsWith<RateLimitedException> { h.registration.verifyEmail(attempt.signUpId!!, "000001", null) }
        assertFailsWith<RateLimitedException> { h.emailChange.confirm(a.id, "ses_1", "000002") }
    }
}
