package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 가입 · 확인의 나머지 규칙 (공격 시나리오는 [SignUpVerificationTest]) */
class RegistrationServiceTest {
    private val h = AccountHarness()

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    @Test
    fun `an accepted sign-up exists even if every queued task is lost - the attempt is stored on the request thread, only the mail is queued`() {
        val lost = AccountHarness(tasks = AccountTaskRunner { _, _ -> })   // a restart, a full queue: the background work vanishes
        val outcome = lost.signUp()
        assertEquals(SignUpStatus.VERIFICATION_SENT, outcome.status)
        assertNotNull(lost.challengeStore.find(lost.challenges.idOf(outcome.signUpId!!)!!), "202 was answered but the attempt does not exist: the sign-up was silently lost")
        assertEquals(0, lost.mailer.sent.size)
    }

    @Test
    fun `a lost code mail is recoverable by resend because the attempt is there`() {
        var dropping = true
        val flaky = AccountHarness(tasks = AccountTaskRunner { _, task -> if (!dropping) task.run() })
        flaky.signUp()
        dropping = false
        flaky.time.advance(Duration.ofSeconds(31))
        flaky.registration.resendVerification(flaky.signUpIds.getValue("ann@example.com"), "203.0.113.1", null)
        flaky.verify()
        assertEquals(AccountStatus.ACTIVE, flaky.repo.findByEmail("ann@example.com")!!.status)
    }

    @Test
    fun `a password that breaks the policy is rejected before anything is queued or stored`() {
        assertEquals("ACCOUNT.PASSWORD_POLICY", code { h.signUp(password = "short1") })
        assertEquals(0, h.mailCount())
        assertNull(h.challengeStore.findOpen(dev.sumin.skeleton.account.challenge.ChallengePurposes.SIGN_UP, "ann@example.com", h.time.now()))
    }

    @Test
    fun `a closed sign-up refuses`() {
        val closed = AccountHarness(AccountProperties(signUp = AccountProperties.SignUp(enabled = false), mail = AccountProperties.Mail(linkBaseUrl = "https://x")))
        assertEquals("ACCOUNT.SIGN_UP_CLOSED", assertFailsWith<ApplicationException> { closed.signUp() }.errorCode.code)
    }

    @Test
    fun `the eleventh sign-up from one IP in an hour is rate limited, other IPs are not`() {
        repeat(10) { h.signUp("u$it@example.com", ip = "198.51.100.9") }
        assertFailsWith<RateLimitedException> { h.signUp("u11@example.com", ip = "198.51.100.9") }
        h.signUp("other@example.com", ip = "198.51.100.10")
    }

    @Test
    fun `captcha is checked first and a failure creates nothing`() {
        val seen = mutableListOf<Triple<String?, String?, String>>()
        val g = AccountHarness(captcha = AccountCaptcha { t, ip, action -> seen += Triple(t, ip, action); t == "ok" })
        assertEquals("ACCOUNT.CAPTCHA_FAILED", assertFailsWith<ApplicationException> { g.signUp(captchaToken = "bad") }.errorCode.code)
        assertEquals(0, g.mailer.sent.size)
        g.signUp(captchaToken = "ok")
        assertEquals(Triple("ok", "203.0.113.1", "sign_up"), seen.last())
    }

    @Test
    fun `the mail language follows the sign-up locale`() {
        h.signUp(locale = "ko-KR")
        assertEquals("ko-KR", h.mailer.of(MailKind.VERIFY_CODE).single().locale)
    }

    // ---- first admin

    private fun bootstrapHarness() = AccountHarness(AccountProperties(
        bootstrap = AccountProperties.Bootstrap(adminEmail = "Boss@Example.com"),
        mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4),
    ))

    @Test
    fun `the configured address becomes ADMIN only once its sign-up code is verified`() {
        val b = bootstrapHarness()
        b.signUp("boss@example.com")
        assertNull(b.repo.findByEmail("boss@example.com"), "an unverified sign-up is not even an account, let alone an admin")
        b.verify("boss@example.com")
        assertEquals(setOf("USER", "ADMIN"), b.repo.findByEmail("boss@example.com")!!.roles)
        assertTrue(AccountEventType.ADMIN_BOOTSTRAPPED in b.events.types())
    }

    @Test
    fun `nobody else is promoted, and once an admin exists the bootstrap is inert`() {
        val b = bootstrapHarness()
        b.activeAccount("someone@example.com")
        assertEquals(setOf("USER"), b.repo.findByEmail("someone@example.com")!!.roles)
        val boss = b.activeAccount("boss@example.com")
        assertTrue("ADMIN" in b.repo.findById(boss.id)!!.roles)
        b.repo.revokeRole(boss.id, "ADMIN", b.time.now())
        b.repo.grantRole(b.repo.findByEmail("someone@example.com")!!.id, "ADMIN", b.time.now())
        b.bootstrap.afterVerified(b.repo.findById(boss.id)!!)
        assertTrue("ADMIN" !in b.repo.findById(boss.id)!!.roles, "an admin already exists")
    }

    @Test
    fun `no bootstrap address configured means no promotion path at all`() {
        val a = h.activeAccount("boss@example.com")
        assertEquals(setOf("USER"), h.repo.findById(a.id)!!.roles)
    }

    // ---- as the auth module's account repository

    @Test
    fun `auth sees an unverified legacy account as blocked and a verified one as open`() {
        val now = h.time.now()
        h.repo.insert(
            Account("acc_old", "old@example.com", false, AccountStatus.PENDING_VERIFICATION, setOf("USER"), null, null, null, now, now),
            listOf(Identity("idn_old", "acc_old", "password", "old@example.com", false, h.hasher.hash("tangerine-42-moon"), null, now)),
        )
        val pending = h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(email = "old@example.com"))!!
        assertEquals(dev.sumin.skeleton.auth.account.LoginBlock.EMAIL_NOT_VERIFIED, pending.loginBlock)
        h.activeAccount()
        val open = h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(email = "ann@example.com"))!!
        assertNull(open.loginBlock)
        assertEquals(setOf("USER"), open.roles)
        assertTrue(h.hasher.matches("tangerine-42-moon", open.passwordHash))
    }

    @Test
    fun `username is the email, accountId works, and unknown ids are null`() {
        val a = h.activeAccount()
        assertEquals(a.id, h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(username = "ann@example.com"))!!.accountId)
        assertEquals("ann@example.com", h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(accountId = a.id))!!.email)
        assertNull(h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(accountId = "acc_nope")))
    }

    @Test
    fun `suspended is blocked, deleted is invisible, and an account without a password has an empty hash`() {
        val a = h.activeAccount()
        h.repo.update(a.id, AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        assertEquals(dev.sumin.skeleton.auth.account.LoginBlock.SUSPENDED, h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(accountId = a.id))!!.loginBlock)
        h.repo.update(a.id, AccountPatch(status = AccountStatus.DELETED), h.time.now())
        assertNull(h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(accountId = a.id)))

        val social = Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, h.time.now(), h.time.now())
        h.repo.insert(social, emptyList())
        assertEquals("", h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(accountId = "acc_s"))!!.passwordHash)
    }

    @Test
    fun `an upgraded hash is written back to the password identity`() {
        val a = h.activeAccount()
        h.authRepository.upgradePasswordHash(a.id, "{bcrypt}newhash")
        assertEquals("{bcrypt}newhash", h.repo.findIdentity("password", "ann@example.com")!!.secret)
    }

    @Test
    fun `a hash upgrade that lost a race with a password reset or proof does not bring the old password back`() {
        val a = h.activeAccount()
        val verified = h.repo.findIdentity("password", "ann@example.com")!!.secret!!   // what the login verified
        h.repo.updateIdentitySecret(h.repo.findIdentity("password", "ann@example.com")!!.id, "{bcrypt}owner-reset-password")   // a reset lands in between
        h.authRepository.upgradePasswordHash(a.id, "{bcrypt}upgraded-old-password", verified)
        assertEquals("{bcrypt}owner-reset-password", h.repo.findIdentity("password", "ann@example.com")!!.secret, "the stored hash was no longer the verified one")
        h.authRepository.upgradePasswordHash(a.id, "{bcrypt}upgraded-owner", "{bcrypt}owner-reset-password")
        assertEquals("{bcrypt}upgraded-owner", h.repo.findIdentity("password", "ann@example.com")!!.secret, "and it still upgrades when nothing changed")
    }

    @Test
    fun `resend is capped per IP like forgot, loudly, and other IPs are not affected`() {
        repeat(10) { h.registration.resendVerification("x".repeat(43), "198.51.100.9", null) }
        assertFailsWith<RateLimitedException> { h.registration.resendVerification("x".repeat(43), "198.51.100.9", null) }
        h.registration.resendVerification("x".repeat(43), "198.51.100.10", null)
    }

    @Test
    fun `captcha required=false never calls the verifier even when one exists`() {
        var calls = 0
        val optional = AccountHarness(captcha = AccountCaptcha { _, _, _ -> calls++; false }, captchaRequired = false)
        optional.signUp()
        assertEquals(0, calls)
        assertEquals(1, optional.mailer.of(MailKind.VERIFY_CODE).size)
    }

    @Test
    fun `captcha required=true without a verifier fails closed instead of letting everything through`() {
        val closed = AccountHarness(captcha = null, captchaRequired = true)
        assertEquals("ACCOUNT.CAPTCHA_FAILED", assertFailsWith<ApplicationException> { closed.signUp() }.errorCode.code)
        assertEquals(0, closed.mailer.sent.size)
    }

    @Test
    fun `the cheap per-IP limit runs before the captcha - a flood costs no external verification calls`() {
        var calls = 0
        val g = AccountHarness(captcha = AccountCaptcha { _, _, _ -> calls++; true })
        repeat(10) { g.signUp("u$it@example.com", ip = "198.51.100.9", captchaToken = "ok") }
        assertEquals(10, calls)
        repeat(5) { assertFailsWith<RateLimitedException> { g.signUp("x$it@example.com", ip = "198.51.100.9", captchaToken = "ok") } }
        assertEquals(10, calls, "requests over the IP limit must not reach the captcha verifier")
        repeat(10) { g.registration.resendVerification("x".repeat(43), "198.51.100.77", "ok") }
        val before = calls
        assertFailsWith<RateLimitedException> { g.registration.resendVerification("x".repeat(43), "198.51.100.77", "ok") }
        assertEquals(before, calls)
    }
}
