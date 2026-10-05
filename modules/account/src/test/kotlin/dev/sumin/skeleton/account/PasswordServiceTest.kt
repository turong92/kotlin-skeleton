package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordServiceTest {
    private val h = AccountHarness()
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun resetTokenFromMail() = h.mailer.tokenOf(h.mailer.of(MailKind.PASSWORD_RESET).last())
    private fun matches(account: Account, raw: String) = h.hasher.matches(raw, h.repo.findIdentity("password", account.email!!)!!.secret!!)

    // ---- forgot

    @Test
    fun `forgot mails a single-use reset link to a known address and nothing to an unknown one`() {
        h.activeAccount()
        h.passwords.forgot("Ann@Example.com", "203.0.113.1", null)
        h.passwords.forgot("nobody@example.com", "203.0.113.1", null)
        val mails = h.mailer.of(MailKind.PASSWORD_RESET)
        assertEquals(1, mails.size)
        assertEquals("ann@example.com", mails.single().to)
        assertTrue(mails.single().link!!.startsWith("https://app.example.com/reset-password?token="))
        assertTrue(AccountEventType.PASSWORD_RESET_REQUESTED in h.events.types())
    }

    @Test
    fun `forgot never touches the stores on the request thread - known and unknown addresses are indistinguishable`() {
        val queue = LinkedBlockingQueue<Runnable>()
        val d = AccountHarness(tasks = AccountTaskRunner { _, t -> queue.add(t) })
        d.signUp(); queue.forEach { it.run() }; queue.clear(); d.verify()
        d.callLog.calls.clear()
        val me = Thread.currentThread()
        d.passwords.forgot("ann@example.com", "203.0.113.1", null)
        val known = d.callLog.by(me)
        d.passwords.forgot("ghost@example.com", "203.0.113.1", null)
        val unknown = d.callLog.by(me)
        assertEquals(emptyList(), known)
        assertEquals(emptyList(), unknown)
        assertEquals(0, d.mailer.of(MailKind.PASSWORD_RESET).size, "mail only after the queued task runs")
        assertEquals(2, queue.size)
    }

    @Test
    fun `forgot is capped per address silently, and per IP loudly with 429`() {
        h.activeAccount()
        repeat(6) { h.passwords.forgot("ann@example.com", "203.0.113.${it + 1}", null) }
        assertEquals(3, h.mailer.of(MailKind.PASSWORD_RESET).size)
        repeat(10) { h.passwords.forgot("x$it@example.com", "198.51.100.1", null) }
        assertFailsWith<RateLimitedException> { h.passwords.forgot("y@example.com", "198.51.100.1", null) }
    }

    @Test
    fun `suspended and deleted accounts get no reset mail`() {
        val a = h.activeAccount()
        h.repo.update(a.id, AccountPatch(status = AccountStatus.SUSPENDED), h.time.now())
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        h.repo.update(a.id, AccountPatch(status = AccountStatus.DELETED), h.time.now())
        h.passwords.forgot("ann@example.com", "203.0.113.2", null)
        assertEquals(0, h.mailer.of(MailKind.PASSWORD_RESET).size)
    }

    // ---- reset

    @Test
    fun `reset replaces the password, signs every session out, tells the owner, and spends the link`() {
        val a = h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        val token = resetTokenFromMail()
        h.passwords.reset(token, "a-brand-new-pass-7")

        assertTrue(matches(a, "a-brand-new-pass-7"))
        assertTrue(!matches(a, "tangerine-42-moon"))
        assertEquals(listOf<Pair<String, String?>>(a.id to null), h.revoker.calls)
        assertEquals(1, h.mailer.of(MailKind.PASSWORD_CHANGED).size)
        assertTrue(AccountEventType.PASSWORD_RESET in h.events.types())
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.passwords.reset(token, "yet-another-pass-8") })
    }

    @Test
    fun `a policy failure leaves the link usable`() {
        h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        val token = resetTokenFromMail()
        assertEquals("ACCOUNT.PASSWORD_POLICY", code { h.passwords.reset(token, "weak") })
        h.passwords.reset(token, "a-brand-new-pass-7")
    }

    @Test
    fun `a newer reset request closes the older link`() {
        h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        val first = resetTokenFromMail()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        assertNotEquals(first, resetTokenFromMail())
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.passwords.reset(first, "a-brand-new-pass-7") })
    }

    @Test
    fun `an expired reset link is dead`() {
        h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        val token = resetTokenFromMail()
        h.time.advance(Duration.ofMinutes(31))
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.passwords.reset(token, "a-brand-new-pass-7") })
    }

    @Test
    fun `a verification link cannot reset a password`() {
        val a = h.activeAccount()
        val verify = h.tokens.issue(TokenPurposes.VERIFY_EMAIL, a.email!!, a.id, Duration.ofHours(1))
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.passwords.reset(verify, "a-brand-new-pass-7") })
    }

    @Test
    fun `a reset link dies when the account email changed after it was issued`() {
        val a = h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        val token = resetTokenFromMail()
        h.repo.changeEmail(a.id, "ann2@example.com", h.time.now())
        assertEquals("ACCOUNT.TOKEN_INVALID", code { h.passwords.reset(token, "a-brand-new-pass-7") })
    }

    @Test
    fun `resetting through the mailbox also proves the address - a pending account becomes active`() {
        h.signUp()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        h.passwords.reset(resetTokenFromMail(), "a-brand-new-pass-7")
        assertEquals(AccountStatus.ACTIVE, h.repo.findByEmail("ann@example.com")!!.status)
    }

    @Test
    fun `an account without a password (social only) can set one through reset`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.passwords.forgot("s@example.com", "203.0.113.1", null)
        h.passwords.reset(resetTokenFromMail(), "a-brand-new-pass-7")
        assertTrue(h.hasher.matches("a-brand-new-pass-7", h.repo.findIdentity("password", "s@example.com")!!.secret!!))
    }

    @Test
    fun `sixteen concurrent resets with one link produce one winner`() {
        h.activeAccount()
        h.passwords.forgot("ann@example.com", "203.0.113.1", null)
        val token = resetTokenFromMail()
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { i -> pool.submit<Result<Unit>> { go.await(); runCatching { h.passwords.reset(token, "winner-pass-$i-x") } } }
        go.countDown()
        assertEquals(1, results.count { it.get().isSuccess })
        pool.shutdown()
    }

    // ---- change

    @Test
    fun `change needs the current password and keeps only the current session`() {
        val a = h.activeAccount()
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.passwords.change(a.id, "wrong-password-1", "a-brand-new-pass-7", "ses_me") })
        assertEquals(emptyList(), h.revoker.calls)

        h.passwords.change(a.id, "tangerine-42-moon", "a-brand-new-pass-7", "ses_me")
        assertTrue(matches(a, "a-brand-new-pass-7"))
        assertEquals(listOf<Pair<String, String?>>(a.id to "ses_me"), h.revoker.calls)
        assertEquals(1, h.mailer.of(MailKind.PASSWORD_CHANGED).size)
        assertTrue(AccountEventType.PASSWORD_CHANGED in h.events.types())
    }

    @Test
    fun `change enforces the policy`() {
        val a = h.activeAccount()
        assertEquals("ACCOUNT.PASSWORD_POLICY", code { h.passwords.change(a.id, "tangerine-42-moon", "weak", null) })
        assertTrue(matches(a, "tangerine-42-moon"))
    }

    @Test
    fun `guessing the current password through a stolen session is throttled per account`() {
        val a = h.activeAccount()
        repeat(10) { assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { h.passwords.change(a.id, "wrong-$it-password", "a-brand-new-pass-7", null) }) }
        assertFailsWith<RateLimitedException> { h.passwords.change(a.id, "tangerine-42-moon", "a-brand-new-pass-7", null) }
    }

    @Test
    fun `an account without a password may set its first one without a current password, once the email is verified`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.passwords.change("acc_s", null, "a-brand-new-pass-7", null)
        assertTrue(h.hasher.matches("a-brand-new-pass-7", h.repo.findIdentity("password", "s@example.com")!!.secret!!))
        assertTrue(h.repo.findIdentity("password", "s@example.com")!!.verified)

        h.repo.insert(Account("acc_u", "u@example.com", false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        assertEquals("ACCOUNT.PASSWORD_REQUIRED", code { h.passwords.change("acc_u", null, "a-brand-new-pass-7", null) })
        assertEquals("ACCOUNT.NOT_FOUND", code { h.passwords.change("acc_nope", null, "a-brand-new-pass-7", null) })
    }
}
