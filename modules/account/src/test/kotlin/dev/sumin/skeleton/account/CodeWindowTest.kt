package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B2 — 코드를 보낸 모든 응답이 카운트다운용 `expiresAt` · `resendAvailableAt` 을 준다: 계정이 이미 있거나 메일을 실제로 안 보냈어도 **같은 모양 · 그럴듯한 값**이다.
 * B3 — 만료된 가입 시도의 다시 받기는 새 코드를 발급해 보낸다 (같은 시도 id, 같은 한도).
 */
class CodeWindowTest {
    private val pw = ReauthInput("tangerine-42-moon")
    private val cooldown = Duration.ofSeconds(30)
    private val ttl = Duration.ofMinutes(10)

    private fun harness(perEmail: Int = 50) = AccountHarness(
        AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
            verification = AccountProperties.Verification(perEmail = perEmail, signUpAttemptsPerEmail = 50, guessesPerEmail = 500),
        ),
    )

    // ---- sign-up

    @Test
    fun `sign-up says when the code expires and when a new one may be asked for, the same for a new and a registered address`() {
        val h = harness()
        h.activeAccount("taken@example.com")
        val free = h.signUp("free@example.com")
        val taken = h.signUp("taken@example.com")
        assertEquals(h.time.now().plus(ttl), free.expiresAt)
        assertEquals(h.time.now().plus(cooldown), free.resendAvailableAt)
        assertEquals(free.expiresAt, taken.expiresAt)
        assertEquals(free.resendAvailableAt, taken.resendAvailableAt)
    }

    @Test
    fun `an address over its mail budget gets the same values although nothing is mailed`() {
        val h = harness(perEmail = 1)
        val sent = h.signUp("ann@example.com")
        val silent = h.signUp("ann@example.com")
        assertEquals(1, h.mailer.of(MailKind.VERIFY_CODE).size)
        assertEquals(sent.expiresAt, silent.expiresAt)
        assertEquals(sent.resendAvailableAt, silent.resendAvailableAt)
    }

    @Test
    fun `a sign-up without email verification has no attempt and so no countdown`() {
        val h = AccountHarness(AccountProperties(signUp = AccountProperties.SignUp(emailVerification = false), password = AccountProperties.Password(bcryptStrength = 4)))
        val o = h.signUp("ann@example.com")
        assertEquals(SignUpStatus.CREATED, o.status)
        assertNull(o.expiresAt); assertNull(o.resendAvailableAt)
    }

    // ---- resend

    @Test
    fun `resend inside the cooldown changes nothing and reports the real remaining time`() {
        val h = harness()
        val o = h.signUp("ann@example.com")
        h.time.advance(Duration.ofSeconds(10))
        val w = h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null)
        assertEquals(1, h.mailer.of(MailKind.VERIFY_CODE).size, "nothing was mailed")
        assertEquals(o.expiresAt, w.expiresAt, "the first code is still the valid one")
        assertEquals(o.resendAvailableAt, w.resendAvailableAt)
    }

    @Test
    fun `a resend after the cooldown mails a new code and reports its new expiry and the next possible resend`() {
        val h = harness()
        val o = h.signUp("ann@example.com")
        h.time.advance(Duration.ofSeconds(31))
        val w = h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null)
        assertEquals(2, h.mailer.of(MailKind.VERIFY_CODE).size)
        assertEquals(h.time.now().plus(ttl), w.expiresAt)
        assertEquals(h.time.now().plus(cooldown), w.resendAvailableAt)
    }

    @Test
    fun `an unknown attempt id gets a plausible window like a real one - the answer reveals nothing about attempts or accounts`() {
        val h = harness()
        val w = h.registration.resendVerification("x".repeat(43), "203.0.113.1", null)
        assertEquals(h.time.now().plus(ttl), w.expiresAt)
        assertEquals(h.time.now().plus(cooldown), w.resendAvailableAt)
        assertEquals(0, h.mailer.sent.size)
    }

    @Test
    fun `the resend window of a registered address equals that of a free one`() {
        val h = harness()
        h.activeAccount("taken@example.com")
        fun window(email: String, ip: String): List<Duration> {
            val o = h.signUp(email, ip = ip)
            h.time.advance(Duration.ofSeconds(31))
            val w = h.registration.resendVerification(o.signUpId!!, ip, null)
            return listOf(Duration.between(h.time.now(), w.expiresAt), Duration.between(h.time.now(), w.resendAvailableAt))
        }
        assertEquals(window("free@example.com", "198.51.100.1"), window("taken@example.com", "198.51.100.2"))
    }

    // ---- B3: expired attempts

    @Test
    fun `an attempt whose code expired is revived by resend - a new code, the same attempt id, a new expiry`() {
        val h = harness()
        val o = h.signUp("ann@example.com")
        val first = h.lastCode("ann@example.com")
        h.time.advance(ttl.plusSeconds(60))
        assertEquals("ACCOUNT.CODE_EXPIRED", assertFailsWith<ApplicationException> { h.registration.verifyEmail(o.signUpId!!, first, "203.0.113.1") }.errorCode.code)

        val w = h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null)
        assertEquals(2, h.mailer.of(MailKind.VERIFY_CODE).size, "a new code was mailed")
        assertEquals(h.time.now().plus(ttl), w.expiresAt, "the expiry is the new one")
        h.registration.verifyEmail(o.signUpId!!, h.lastCode("ann@example.com"), "203.0.113.1")
        assertNotNull(h.repo.findByEmail("ann@example.com"), "the same attempt id and the password typed in it finish the sign-up")
    }

    @Test
    fun `reviving an expired attempt is bounded like any resend - three per attempt, the mail budget, and the guess ceiling`() {
        val h = harness(perEmail = 100)
        val o = h.signUp("ann@example.com")
        repeat(5) { h.time.advance(ttl.plusSeconds(1)); h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null) }
        assertEquals(4, h.mailer.of(MailKind.VERIFY_CODE).size, "the first mail plus three resends, expired or not")

        val budgeted = harness(perEmail = 1)
        val b = budgeted.signUp("bob@example.com")
        budgeted.time.advance(ttl.plusSeconds(1))
        budgeted.registration.resendVerification(b.signUpId!!, "203.0.113.2", null)
        assertEquals(1, budgeted.mailer.of(MailKind.VERIFY_CODE).size, "the per-address mail budget still applies to a revival")
    }

    @Test
    fun `a revived attempt gives a fresh set of guesses but the per-address guess ceiling still counts every one`() {
        val h = AccountHarness(
            AccountProperties(
                mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
                verification = AccountProperties.Verification(perEmail = 100, signUpAttemptsPerEmail = 50, guessesPerEmail = 6),
            ),
        )
        val o = h.signUp("ann@example.com")
        val wrong = if (h.lastCode("ann@example.com") == "000000") "000001" else "000000"
        repeat(5) { assertFailsWith<ApplicationException> { h.registration.verifyEmail(o.signUpId!!, wrong, null) } }
        h.time.advance(ttl.plusSeconds(1))
        h.registration.resendVerification(o.signUpId!!, "203.0.113.1", null)
        assertFailsWith<ApplicationException> { h.registration.verifyEmail(o.signUpId!!, if (h.lastCode("ann@example.com") == wrong) "000002" else wrong, null) }   // 6th guess in the window
        val over = assertFailsWith<ApplicationException> { h.registration.verifyEmail(o.signUpId!!, "000003", null) }
        assertEquals(429, over.errorCode.status.value(), "the 7th guess for this address in the window is refused")
    }

    // ---- the other code-sending endpoints

    @Test
    fun `email change, re-authentication and deletion confirmation report the code window, also when nothing is mailed`() {
        val h = harness()
        val a = h.activeAccount()
        val change = h.emailChange.request(a.id, "new@example.com", pw, "ses_1")
        assertEquals(h.time.now().plus(ttl), change.expiresAt)
        assertEquals(h.time.now().plus(cooldown), change.resendAvailableAt)
        // an address somebody else owns: nothing is mailed, the window is the same
        h.activeAccount("other@example.com")
        val taken = h.emailChange.request(a.id, "other@example.com", pw, "ses_1")
        assertEquals(change, taken)

        assertEquals(change, h.reauth.requestConfirmation(a.id, "ses_1"))
        assertEquals(change, h.deletion.requestConfirmation(a.id, "ses_1"))

        // an account without an address: nothing is mailed, still the same window
        val now = h.time.now()
        h.repo.insert(Account("acc_noaddr", null, false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        assertEquals(change, h.reauth.requestConfirmation("acc_noaddr", "ses_2"))
        assertEquals(change, h.deletion.requestConfirmation("acc_noaddr", "ses_2"))
        assertNotEquals(0, h.mailer.of(MailKind.REAUTH_CODE).size)
        assertTrue(h.mailer.of(MailKind.REAUTH_CODE).none { it.to.isEmpty() })
    }
}
