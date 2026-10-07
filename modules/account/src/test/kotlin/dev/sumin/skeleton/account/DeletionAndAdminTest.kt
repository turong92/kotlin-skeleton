package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
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

class DeletionAndAdminTest {
    private val h = AccountHarness()
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    // ---- deletion

    @Test
    fun `deleting needs the password, schedules the purge after the grace, closes sessions and tells the owner`() {
        val a = h.activeAccount()
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.deletion.delete(a.id, ReauthInput("wrong-password-1"), null) })
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(a.id)!!.status)

        val scheduled = h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        val after = h.repo.findById(a.id)!!
        assertEquals(AccountStatus.DELETED, after.status)
        assertEquals(h.time.now().plus(Duration.ofDays(30)), after.purgeAfter)
        assertEquals(after.purgeAfter, scheduled)
        assertEquals(listOf<Pair<String, String?>>(a.id to null), h.revoker.calls)
        assertEquals(1, h.mailer.of(MailKind.DELETION_SCHEDULED).size)
        assertTrue(AccountEventType.DELETION_SCHEDULED in h.events.types())
        assertNull(h.authRepository.findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(accountId = a.id)), "a deleted account is invisible to login")
    }

    @Test
    fun `deleting again is idempotent and does not extend the grace`() {
        val a = h.activeAccount()
        val first = h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(Duration.ofDays(3))
        assertEquals(first, h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null))
    }

    @Test
    fun `an account without a password confirms with a mailed six digit code entered in the same session`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.deletion.delete("acc_s", ReauthInput(), "ses_1") })
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.deletion.delete("acc_s", ReauthInput(confirmationCode = "123456"), "ses_1") }, "no code was requested")

        h.deletion.requestConfirmation("acc_s", "ses_1")
        val mail = h.mailer.of(MailKind.DELETE_CODE).single()
        assertEquals("s@example.com", mail.to)
        assertNull(mail.link)
        val sent = mail.vars.getValue("code")
        val wrong = if (sent == "000000") "000001" else "000000"
        val e = assertFailsWith<ApplicationException> { h.deletion.delete("acc_s", ReauthInput(confirmationCode = wrong), "ses_1") }
        assertEquals("ACCOUNT.CODE_INVALID", e.errorCode.code)
        assertEquals(mapOf("attemptsLeft" to 4), e.data)
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.deletion.delete("acc_s", ReauthInput(confirmationCode = sent), "ses_other") }, "a code is bound to the session that asked for it")
        h.deletion.requestConfirmation("acc_s", "ses_1")
        val fresh = h.mailer.of(MailKind.DELETE_CODE).last().vars.getValue("code")
        h.deletion.delete("acc_s", ReauthInput(confirmationCode = fresh), "ses_1")
        assertEquals(AccountStatus.DELETED, h.repo.findById("acc_s")!!.status)
        h.admin.restore("acc_admin", "acc_s")
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.deletion.delete("acc_s", ReauthInput(confirmationCode = fresh), "ses_1") }, "the code is single use")
    }

    @Test
    fun `a delete code of another account does not authorize deletion`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_a", "a@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.repo.insert(Account("acc_b", "b@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        h.deletion.requestConfirmation("acc_a", "ses_1")
        val sent = h.mailer.of(MailKind.DELETE_CODE).single().vars.getValue("code")
        assertEquals("ACCOUNT.CODE_EXPIRED", code { h.deletion.delete("acc_b", ReauthInput(confirmationCode = sent), "ses_1") })
        assertEquals(AccountStatus.ACTIVE, h.repo.findById("acc_b")!!.status)
    }

    @Test
    fun `the only administrator cannot delete themself`() {
        val a = h.activeAccount()
        h.repo.grantRole(a.id, "ADMIN", h.time.now())
        assertEquals("ACCOUNT.LAST_ADMIN", code { h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null) })
    }

    @Test
    fun `an admin restores a deleted account within the grace, not after the purge`() {
        val a = h.activeAccount()
        h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        h.admin.restore("acc_admin", a.id)
        val back = h.repo.findById(a.id)!!
        assertEquals(AccountStatus.ACTIVE, back.status)
        assertNull(back.purgeAfter)
        assertTrue(AccountEventType.ACCOUNT_RESTORED in h.events.types())

        h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(Duration.ofDays(31))
        h.purge.purgeDue()
        assertEquals("ACCOUNT.ERASED", code { h.admin.restore("acc_admin", a.id) })
    }

    // ---- purge + erasure

    @Test
    fun `purge waits for the grace, runs every erasure listener with a tombstone, then erases the account's personal data`() {
        val a = h.activeAccount()
        val seen = mutableListOf<ErasureRequest>()
        h.erasers += object : AccountErasureListener { override val name = "board"; override fun erase(request: ErasureRequest) { seen += request } }
        h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)

        h.time.advance(Duration.ofDays(29))
        assertEquals(0, h.purge.purgeDue())
        assertNotNull(h.repo.findById(a.id))

        h.time.advance(Duration.ofDays(2))
        assertEquals(1, h.purge.purgeDue())
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
        assertNull(h.repo.findIdentity("password", "ann@example.com"))
        assertEquals(listOf(a.id), seen.map { it.accountId })
        assertEquals(AccountTombstone.of(a.id), seen.single().tombstone)
        assertTrue(AccountTombstone.isTombstone(seen.single().tombstone))
        assertTrue(a.id !in seen.single().tombstone, "the tombstone must not contain the account id")
        assertTrue(AccountEventType.ACCOUNT_PURGED in h.events.types())
    }

    @Test
    fun `a failing listener keeps the account for the next run instead of half-erasing it`() {
        val a = h.activeAccount()
        var broken = true
        val calls = mutableListOf<String>()
        h.erasers += object : AccountErasureListener { override val name = "flaky"; override fun erase(request: ErasureRequest) { calls += "flaky"; if (broken) error("db down") } }
        h.erasers += object : AccountErasureListener { override val name = "notify"; override fun erase(request: ErasureRequest) { calls += "notify" } }
        h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(Duration.ofDays(31))

        assertEquals(0, h.purge.purgeDue())
        assertNotNull(h.repo.findById(a.id), "must stay so the erasure can be retried")
        broken = false
        assertEquals(1, h.purge.purgeDue())
        assertEquals(AccountStatus.ERASED, h.repo.findById(a.id)!!.status)
        assertTrue(calls.count { it == "flaky" } == 2)
    }

    @Test
    fun `the tombstone is stable per account and different between accounts`() {
        assertEquals(AccountTombstone.of("acc_1"), AccountTombstone.of("acc_1"))
        assertTrue(AccountTombstone.of("acc_1") != AccountTombstone.of("acc_2"))
        assertTrue(!AccountTombstone.isTombstone("acc_1"))
    }

    // ---- admin

    private fun admin(): Account {
        val a = h.activeAccount("admin@example.com")
        h.repo.grantRole(a.id, "ADMIN", h.time.now())
        return h.repo.findById(a.id)!!
    }

    @Test
    fun `suspending closes sessions, unsuspending reopens, both are recorded`() {
        val admin = admin()
        val user = h.activeAccount("ann@example.com")
        h.admin.suspend(admin.id, user.id, "spam")
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById(user.id)!!.status)
        assertEquals("spam", h.repo.findById(user.id)!!.suspendedReason)
        assertTrue(h.revoker.calls.contains(user.id to null))
        assertTrue(AccountEventType.ACCOUNT_SUSPENDED in h.events.types())

        h.admin.unsuspend(admin.id, user.id)
        assertEquals(AccountStatus.ACTIVE, h.repo.findById(user.id)!!.status)
        assertNull(h.repo.findById(user.id)!!.suspendedReason)
        assertTrue(AccountEventType.ACCOUNT_UNSUSPENDED in h.events.types())
    }

    @Test
    fun `an admin cannot suspend themself and the last admin cannot be suspended`() {
        val admin = admin()
        assertEquals("ACCOUNT.SELF_ACTION_FORBIDDEN", code { h.admin.suspend(admin.id, admin.id, null) })
        val other = h.activeAccount("other@example.com")
        h.admin.grantRole(admin.id, other.id, "ADMIN")
        h.admin.suspend(admin.id, other.id, null)
        val third = h.activeAccount("third@example.com")
        h.admin.grantRole(admin.id, third.id, "ADMIN")
        h.admin.suspend(third.id, admin.id, null)
        assertEquals("ACCOUNT.LAST_ADMIN", code { h.admin.suspend("someone-else", third.id, null) })
    }

    @Test
    fun `roles are granted and revoked, the last ADMIN cannot be revoked, and role names are validated`() {
        val admin = admin()
        val user = h.activeAccount("ann@example.com")
        h.admin.grantRole(admin.id, user.id, "MODERATOR")
        assertTrue("MODERATOR" in h.repo.findById(user.id)!!.roles)
        assertTrue(AccountEventType.ROLE_GRANTED in h.events.types())
        h.admin.revokeRole(admin.id, user.id, "MODERATOR")
        assertTrue("MODERATOR" !in h.repo.findById(user.id)!!.roles)
        assertEquals("ACCOUNT.LAST_ADMIN", code { h.admin.revokeRole(user.id, admin.id, "ADMIN") })
        assertFailsWith<ApplicationException> { h.admin.grantRole(admin.id, user.id, "bad role!") }
    }

    @Test
    fun `granting a role to a deleted account is not found, like every other admin action on it`() {
        val admin = admin()
        val gone = h.activeAccount("gone@example.com")
        h.deletion.delete(gone.id, ReauthInput("tangerine-42-moon"), null)
        assertEquals("ACCOUNT.NOT_FOUND", code { h.admin.grantRole(admin.id, gone.id, "ADMIN") })
        assertTrue("ADMIN" !in h.repo.findById(gone.id)!!.roles)
    }

    @Test
    fun `two administrators suspending or demoting each other at once never leave zero administrators`() {
        repeat(40) { round ->
            val x = AccountHarness()
            val a = x.activeAccount("a$round@example.com"); val b = x.activeAccount("b$round@example.com")
            x.repo.grantRole(a.id, "ADMIN", x.time.now()); x.repo.grantRole(b.id, "ADMIN", x.time.now())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            val go = java.util.concurrent.CountDownLatch(1)
            val suspend = round % 2 == 0
            val jobs = listOf(a.id to b.id, b.id to a.id).map { (actor, target) ->
                pool.submit { go.await(); runCatching { if (suspend) x.admin.suspend(actor, target, null) else x.admin.revokeRole(actor, target, "ADMIN") } }
            }
            go.countDown(); jobs.forEach { it.get() }; pool.shutdown()
            assertEquals(1L, x.repo.countActiveWithRole("ADMIN"), "round $round: the last-administrator check was a read followed by a write")
        }
    }

    @Test
    fun `restoring after the grace ended is refused even before the purge job has run - purge and restore cannot both win`() {
        val a = h.activeAccount()
        h.deletion.delete(a.id, ReauthInput("tangerine-42-moon"), null)
        h.time.advance(Duration.ofDays(31))
        assertEquals("ACCOUNT.NOT_FOUND", code { h.admin.restore("acc_admin", a.id) })
        assertEquals(AccountStatus.DELETED, h.repo.findById(a.id)!!.status)
    }

    @Test
    fun `search filters by email fragment and status`() {
        admin(); h.activeAccount("ann@example.com")
        val now = h.time.now()
        h.repo.insert(Account("acc_pending", "pending@example.com", false, AccountStatus.PENDING_VERIFICATION, setOf("USER"), null, null, null, now, now), emptyList())
        assertEquals(1, h.admin.search("ann", null, 0, 10).total)
        assertEquals(1, h.admin.search(null, AccountStatus.PENDING_VERIFICATION, 0, 10).total)
        assertEquals(3, h.admin.search(null, null, 0, 10).total)
    }

    @Test
    fun `unknown targets are not found`() {
        val admin = admin()
        assertEquals("ACCOUNT.NOT_FOUND", code { h.admin.suspend(admin.id, "acc_nope", null) })
        assertEquals("ACCOUNT.NOT_FOUND", code { h.admin.get("acc_nope") })
    }

    // ---- profile

    @Test
    fun `me shows the profile with sign-in methods and whether there is a password`() {
        val a = h.activeAccount()
        val me = h.profile.me(a.id)
        assertEquals("ann@example.com", me.email)
        assertEquals(true, me.emailVerified)
        assertEquals(true, me.hasPassword)
        assertEquals(listOf("password"), me.methods.map { it.method })
        assertEquals("Asia/Seoul", me.timeZone)
    }

    @Test
    fun `profile updates take locale and time zone, trim names, and reject nonsense`() {
        val a = h.activeAccount()
        val me = h.profile.update(a.id, ProfileChange(displayName = "  Ann B  ", locale = "ko-KR", timeZone = "America/New_York"))
        assertEquals("Ann B", me.displayName)
        assertEquals("ko-KR", me.locale)
        assertEquals("America/New_York", me.timeZone)
        assertFailsWith<ApplicationException> { h.profile.update(a.id, ProfileChange(locale = "not a locale")) }
        assertFailsWith<ApplicationException> { h.profile.update(a.id, ProfileChange(timeZone = "Mars/Olympus")) }
        assertFailsWith<ApplicationException> { h.profile.update(a.id, ProfileChange(displayName = "x".repeat(61))) }
        assertEquals("Ann B", h.profile.me(a.id).displayName, "a rejected update changes nothing")
    }
}
