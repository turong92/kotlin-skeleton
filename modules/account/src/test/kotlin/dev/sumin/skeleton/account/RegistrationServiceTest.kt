package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationServiceTest {
    private val h = AccountHarness()

    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    @Test
    fun `sign-up creates a pending account with a hashed password and mails a verification link`() {
        assertEquals(SignUpStatus.VERIFICATION_SENT, h.signUp())
        val account = h.repo.findByEmail("ann@example.com")!!
        assertEquals(AccountStatus.PENDING_VERIFICATION, account.status)
        assertEquals(false, account.emailVerified)
        assertEquals(setOf("USER"), account.roles)
        assertEquals("Ann", account.displayName)

        val identity = h.repo.findIdentity("password", "ann@example.com")!!
        assertTrue(identity.secret!!.startsWith("{bcrypt}"))
        assertTrue("tangerine-42-moon" !in identity.secret!!)
        assertEquals(false, identity.verified)

        val mail = h.mailer.of(MailKind.VERIFY_EMAIL).single()
        assertEquals("ann@example.com", mail.to)
        assertTrue(mail.link!!.startsWith("https://app.example.com/verify-email?token="))
        assertTrue(AccountEventType.SIGN_UP in h.events.types())
    }

    @Test
    fun `an already-registered address gets the same answer, no second account and an 'already registered' mail`() {
        h.signUp()
        assertEquals(SignUpStatus.VERIFICATION_SENT, h.signUp(password = "a-different-pass-9"))
        assertEquals(1, h.repo.search(null, null, 0, 10).total)
        assertTrue(h.hasher.matches("tangerine-42-moon", h.repo.findIdentity("password", "ann@example.com")!!.secret!!), "the existing password must not change")
        assertEquals(1, h.mailer.of(MailKind.ALREADY_REGISTERED).size)
    }

    @Test
    fun `emails are compared trimmed and case-insensitively`() {
        h.signUp("  Ann@Example.COM ")
        h.signUp("ann@example.com")
        assertEquals(1, h.repo.search(null, null, 0, 10).total)
        assertNotNull(h.repo.findByEmail("ann@example.com"))
    }

    @Test
    fun `the request thread never touches the account store or the mailer - known and unknown addresses look identical`() {
        val queue = LinkedBlockingQueue<Runnable>()
        val deferred = AccountHarness(tasks = AccountTaskRunner { _, task -> queue.add(task) })
        val me = Thread.currentThread()

        deferred.signUp("new@example.com")
        val unknownCalls = deferred.callLog.by(me)
        deferred.callLog.calls.clear(); queue.forEach { it.run() }; queue.clear(); deferred.callLog.calls.clear()

        deferred.signUp("new@example.com")   // now it exists
        val knownCalls = deferred.callLog.by(me)

        assertEquals(unknownCalls, knownCalls)
        assertEquals(emptyList(), knownCalls, "no repository call may happen on the request thread")
        assertEquals(0, deferred.mailer.sent.size - deferred.mailer.of(MailKind.VERIFY_EMAIL).size, "nothing but the first (deferred) mail so far")
    }

    @Test
    fun `a password that breaks the policy is rejected before anything is queued or stored`() {
        assertEquals("ACCOUNT.PASSWORD_POLICY", code { h.signUp(password = "short1") })
        assertNull(h.repo.findByEmail("ann@example.com"))
        assertEquals(0, h.mailCount())
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
        assertNull(g.repo.findByEmail("ann@example.com"))
        g.signUp(captchaToken = "ok")
        assertEquals(Triple("ok", "203.0.113.1", "sign_up"), seen.last())
    }

    @Test
    fun `verification activates the account and the identity, once`() {
        h.signUp()
        val token = h.mailer.tokenOf(h.mailer.of(MailKind.VERIFY_EMAIL).single())
        h.registration.verifyEmail(token)

        val account = h.repo.findByEmail("ann@example.com")!!
        assertEquals(AccountStatus.ACTIVE, account.status)
        assertTrue(account.emailVerified)
        assertTrue(h.repo.findIdentity("password", "ann@example.com")!!.verified)
        assertTrue(AccountEventType.EMAIL_VERIFIED in h.events.types())
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.registration.verifyEmail(token) })
    }

    @Test
    fun `sixteen concurrent verifications of one link produce one success`() {
        h.signUp()
        val token = h.mailer.tokenOf(h.mailer.of(MailKind.VERIFY_EMAIL).single())
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { pool.submit<Result<Unit>> { go.await(); runCatching { h.registration.verifyEmail(token) } } }
        go.countDown()
        assertEquals(1, results.count { it.get().isSuccess })
        pool.shutdown()
    }

    @Test
    fun `an expired verification link is dead`() {
        h.signUp()
        val token = h.mailer.tokenOf(h.mailer.of(MailKind.VERIFY_EMAIL).single())
        h.time.advance(Duration.ofHours(25))
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.registration.verifyEmail(token) })
    }

    @Test
    fun `a password-reset token cannot verify an email`() {
        val a = h.activeAccount()
        val reset = h.tokens.issue("password_reset", a.email!!, a.id, Duration.ofHours(1))
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.registration.verifyEmail(reset) })
    }

    @Test
    fun `resend closes the old link, sends a new one, and stays silent for unknown or verified addresses`() {
        h.signUp()
        val first = h.mailer.tokenOf(h.mailer.of(MailKind.VERIFY_EMAIL).single())
        h.registration.resendVerification("ann@example.com", "203.0.113.1", null)
        assertEquals(2, h.mailer.of(MailKind.VERIFY_EMAIL).size)
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.registration.verifyEmail(first) })

        h.registration.resendVerification("nobody@example.com", "203.0.113.1", null)
        assertEquals(2, h.mailer.of(MailKind.VERIFY_EMAIL).size)
        h.verify()
        h.registration.resendVerification("ann@example.com", "203.0.113.1", null)
        assertEquals(2, h.mailer.of(MailKind.VERIFY_EMAIL).size)
    }

    @Test
    fun `resend is capped per address and silently so`() {
        h.signUp()
        repeat(6) { h.registration.resendVerification("ann@example.com", "203.0.113.1", null) }
        assertEquals(3, h.mailer.of(MailKind.VERIFY_EMAIL).size, "1 sign-up mail + 2 resends = the per-email cap of 3 per hour")
    }

    @Test
    fun `without email verification the account is active at once and a duplicate is a 409`() {
        val open = AccountHarness(AccountProperties(
            signUp = AccountProperties.SignUp(emailVerification = false),
            mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4),
        ))
        assertEquals(SignUpStatus.CREATED, open.signUp())
        assertEquals(AccountStatus.ACTIVE, open.repo.findByEmail("ann@example.com")!!.status)
        assertEquals(0, open.mailer.of(MailKind.VERIFY_EMAIL).size)
        assertEquals("ACCOUNT.EMAIL_TAKEN", assertFailsWith<ApplicationException> { open.signUp() }.errorCode.code)
    }

    @Test
    fun `the mail language follows the account locale`() {
        h.signUp(locale = "ko-KR")
        assertEquals("ko-KR", h.mailer.of(MailKind.VERIFY_EMAIL).single().locale)
    }

    // ---- first admin

    private fun bootstrapHarness() = AccountHarness(AccountProperties(
        bootstrap = AccountProperties.Bootstrap(adminEmail = "Boss@Example.com"),
        mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4),
    ))

    @Test
    fun `the configured address becomes ADMIN only after its email is verified`() {
        val b = bootstrapHarness()
        b.signUp("boss@example.com")
        assertEquals(setOf("USER"), b.repo.findByEmail("boss@example.com")!!.roles, "unverified sign-up must never be promoted")
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
    fun `auth sees a pending account as blocked and a verified one as open`() {
        h.signUp()
        val pending = h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(email = "ann@example.com"))!!
        assertEquals(dev.sumin.skeleton.auth.account.LoginBlock.EMAIL_NOT_VERIFIED, pending.loginBlock)
        h.verify()
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
}
