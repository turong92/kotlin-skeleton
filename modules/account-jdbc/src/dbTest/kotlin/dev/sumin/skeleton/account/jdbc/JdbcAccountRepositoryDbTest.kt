package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.ChangeEmailResult
import dev.sumin.skeleton.account.GuardedResult
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.RemoveIdentityResult
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcAccountRepositoryDbTest {
    private val repo = AccountDb.accounts
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")

    @BeforeTest fun clean() = AccountDb.clean()

    private fun account(id: String = "acc_1", email: String? = "ann@example.com", status: AccountStatus = AccountStatus.ACTIVE, roles: Set<String> = setOf("USER"), verified: Boolean = true) =
        Account(id, email, verified, status, roles, "Ann", "ko", "Asia/Seoul", now, now)

    private fun identity(id: String, account: String = "acc_1", method: String = "password", subject: String = "ann@example.com", secret: String? = "{bcrypt}hash") =
        Identity(id, account, method, subject, true, secret, null, now)

    @Test
    fun `an account with roles and identities round-trips with microsecond instants`() {
        assertTrue(repo.insert(account(roles = setOf("USER", "ADMIN")), listOf(identity("idn_1"))))
        val back = repo.findById("acc_1")!!
        assertEquals(setOf("USER", "ADMIN"), back.roles)
        assertEquals(now, back.createdAt)
        assertEquals("Asia/Seoul", back.timeZone)
        assertEquals("acc_1", repo.findByEmail("ann@example.com")!!.id)
        assertEquals("acc_1", repo.findByIdentity("password", "ann@example.com")!!.id)
        assertEquals("{bcrypt}hash", repo.findIdentity("password", "ann@example.com")!!.secret)
        assertEquals(now, repo.findIdentity("password", "ann@example.com")!!.createdAt)
    }

    @Test
    fun `a duplicate email is refused by the unique key and leaves nothing behind - roles and identities included`() {
        assertTrue(repo.insert(account(), listOf(identity("idn_1"))))
        assertFalse(repo.insert(account("acc_2"), listOf(identity("idn_2", "acc_2", subject = "other@example.com"))))
        assertNull(repo.findById("acc_2"))
        assertNull(repo.findIdentity("password", "other@example.com"))
    }

    @Test
    fun `a duplicate identity subject rolls the whole account back`() {
        assertTrue(repo.insert(account(), listOf(identity("idn_1", method = "google", subject = "g-1"))))
        assertFalse(repo.insert(account("acc_2", "two@example.com"), listOf(identity("idn_2", "acc_2", "google", "g-1"))))
        assertNull(repo.findById("acc_2"))
    }

    @Test
    fun `accounts without an email can be many - and identity subjects are case sensitive`() {
        assertTrue(repo.insert(account("acc_a", null), listOf(identity("idn_a", "acc_a", "kakao", "AbC"))))
        assertTrue(repo.insert(account("acc_b", null), listOf(identity("idn_b", "acc_b", "kakao", "abc"))))
        assertEquals("acc_a", repo.findByIdentity("kakao", "AbC")!!.id)
        assertEquals("acc_b", repo.findByIdentity("kakao", "abc")!!.id)
    }

    @Test
    fun `sixteen simultaneous inserts of one email make exactly one account`() {
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val wins = (1..16).map { i -> pool.submit<Boolean> { go.await(); repo.insert(account("acc_$i"), listOf(identity("idn_$i", "acc_$i"))) } }
        go.countDown()
        assertEquals(1, wins.count { it.get() })
        pool.shutdown()
        assertEquals(1, repo.search(null, null, 0, 50).total)
    }

    @Test
    fun `verifying the email activates a pending account and verifies the email-bound identities together`() {
        repo.insert(account(status = AccountStatus.PENDING_VERIFICATION, verified = false), listOf(identity("idn_1").copy(verified = false), identity("idn_2", method = "google", subject = "g-1").copy(verified = false)))
        assertTrue(repo.markEmailVerified("acc_1", now))
        assertEquals(AccountStatus.ACTIVE, repo.findById("acc_1")!!.status)
        assertTrue(repo.findById("acc_1")!!.emailVerified)
        assertTrue(repo.findIdentity("password", "ann@example.com")!!.verified)
        assertFalse(repo.findIdentity("google", "g-1")!!.verified, "a provider identity is not about the account email")
    }

    @Test
    fun `verifying does not reactivate a suspended account`() {
        repo.insert(account(status = AccountStatus.SUSPENDED, verified = false), listOf(identity("idn_1")))
        repo.markEmailVerified("acc_1", now)
        assertEquals(AccountStatus.SUSPENDED, repo.findById("acc_1")!!.status)
    }

    @Test
    fun `changing the email moves the account and the email-bound identities atomically, or says it is taken`() {
        repo.insert(account(), listOf(identity("idn_1")))
        repo.insert(account("acc_2", "bob@example.com"), listOf(identity("idn_2", "acc_2", subject = "bob@example.com")))
        assertEquals(ChangeEmailResult.TAKEN, repo.changeEmail("acc_1", "bob@example.com", now))
        assertEquals("ann@example.com", repo.findById("acc_1")!!.email)
        assertNotNull(repo.findIdentity("password", "ann@example.com"))

        assertEquals(ChangeEmailResult.CHANGED, repo.changeEmail("acc_1", "new@example.com", now))
        assertEquals("new@example.com", repo.findById("acc_1")!!.email)
        assertEquals("acc_1", repo.findByIdentity("password", "new@example.com")!!.id)
        assertNull(repo.findIdentity("password", "ann@example.com"))
        assertEquals(ChangeEmailResult.NOT_FOUND, repo.changeEmail("acc_nope", "x@example.com", now))
    }

    @Test
    fun `partial updates leave other fields alone and deletion fields can be cleared`() {
        repo.insert(account(), listOf(identity("idn_1")))
        val later = now.plus(Duration.ofHours(1))
        repo.update("acc_1", AccountPatch(displayName = "Ann B", status = AccountStatus.DELETED, deletedAt = later, purgeAfter = later.plus(Duration.ofDays(30)), lastLoginAt = later), later)
        val a = repo.findById("acc_1")!!
        assertEquals("Ann B", a.displayName)
        assertEquals("ko", a.locale)
        assertEquals(later.plus(Duration.ofDays(30)), a.purgeAfter)
        repo.update("acc_1", AccountPatch(status = AccountStatus.ACTIVE, clearDeletion = true), later)
        assertNull(repo.findById("acc_1")!!.purgeAfter)
        assertNull(repo.findById("acc_1")!!.deletedAt)
        assertNull(repo.update("acc_nope", AccountPatch(displayName = "x"), later))
    }

    @Test
    fun `roles are granted once, revoked once, and counted for active accounts only`() {
        repo.insert(account(), listOf(identity("idn_1")))
        assertTrue(repo.grantRole("acc_1", "ADMIN", now))
        assertFalse(repo.grantRole("acc_1", "ADMIN", now))
        assertEquals(1, repo.countActiveWithRole("ADMIN"))
        repo.update("acc_1", AccountPatch(status = AccountStatus.SUSPENDED), now)
        assertEquals(0, repo.countActiveWithRole("ADMIN"))
        assertTrue(repo.revokeRole("acc_1", "ADMIN", now))
        assertFalse(repo.revokeRole("acc_1", "ADMIN", now))
    }

    @Test
    fun `search filters by email fragment and status, newest first, and pages`() {
        repeat(5) { i -> repo.insert(account("acc_$i", "user$i@example.com").copy(createdAt = now.plusSeconds(i.toLong())), listOf(identity("idn_$i", "acc_$i", subject = "user$i@example.com"))) }
        repo.insert(account("acc_p", "pending@corp.test", AccountStatus.PENDING_VERIFICATION, verified = false), emptyList())
        val page = repo.search("example", null, 0, 2)
        assertEquals(5, page.total)
        assertEquals(listOf("acc_4", "acc_3"), page.items.map { it.id })
        assertEquals(listOf("acc_p"), repo.search(null, AccountStatus.PENDING_VERIFICATION, 0, 10).items.map { it.id })
        assertEquals(0, repo.search("100%_", null, 0, 10).total, "LIKE wildcards in the fragment are literal")
    }

    @Test
    fun `due accounts are found by purge time and purging cascades to roles and identities`() {
        repo.insert(account(), listOf(identity("idn_1")))
        repo.update("acc_1", AccountPatch(status = AccountStatus.DELETED, deletedAt = now, purgeAfter = now.plus(Duration.ofDays(30))), now)
        assertEquals(emptyList(), repo.dueForPurge(now.plus(Duration.ofDays(29)), 10).map { it.id })
        assertEquals(listOf("acc_1"), repo.dueForPurge(now.plus(Duration.ofDays(31)), 10).map { it.id })
        assertTrue(repo.purge("acc_1", now.plus(Duration.ofDays(31))))
        assertNull(repo.findById("acc_1"))
        assertNull(repo.findIdentity("password", "ann@example.com"))
        assertEquals(0, AccountDb.jdbc.queryForObject("select count(*) from account_roles", emptyMap<String, Any>(), Int::class.java))
    }

    @Test
    fun `two simultaneous unlinks of an account's only two methods leave one`() {
        repeat(10) { round ->
            AccountDb.clean()
            repo.insert(account(), listOf(identity("idn_p$round"), identity("idn_g$round", method = "google", subject = "g-$round")))
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val r = listOf("idn_p$round", "idn_g$round").map { id -> pool.submit<RemoveIdentityResult> { go.await(); repo.removeIdentityUnlessLast("acc_1", id, setOf("password", "google")) } }
            go.countDown()
            val results = r.map { it.get() }
            pool.shutdown()
            assertEquals(1, results.count { it == RemoveIdentityResult.REMOVED }, results.toString())
            assertEquals(1, results.count { it == RemoveIdentityResult.LAST }, results.toString())
            assertEquals(1, repo.identitiesOf("acc_1").size)
        }
    }

    @Test
    fun `removing someone else's identity, a missing one, and secrets and touches`() {
        repo.insert(account(), listOf(identity("idn_1"), identity("idn_2", method = "google", subject = "g-1")))
        assertEquals(RemoveIdentityResult.NOT_FOUND, repo.removeIdentityUnlessLast("acc_other", "idn_1", setOf("password")))
        assertEquals(RemoveIdentityResult.NOT_FOUND, repo.removeIdentityUnlessLast("acc_1", "idn_nope", setOf("password")))
        assertTrue(repo.updateIdentitySecret("idn_1", "{bcrypt}new"))
        repo.touchIdentity("idn_1", now.plusSeconds(5))
        val back = repo.findIdentityById("idn_1")!!
        assertEquals("{bcrypt}new", back.secret)
        assertEquals(now.plusSeconds(5), back.lastUsedAt)
        assertTrue(repo.removeIdentity("acc_1", "idn_2"))
        assertFalse(repo.removeIdentity("acc_1", "idn_2"))
        assertTrue(repo.addIdentity(identity("idn_3", method = "kakao", subject = "k-1")))
        assertFalse(repo.addIdentity(identity("idn_4", method = "kakao", subject = "k-1")))
    }

    @Test
    fun `purge only removes a deleted account whose grace is over, restore only reopens one whose grace is not over`() {
        repo.insert(account(), listOf(identity("idn_1")))
        assertFalse(repo.purge("acc_1", now.plus(Duration.ofDays(99))), "an ACTIVE account must never be purged (a restore won the race)")
        assertNotNull(repo.findById("acc_1"))

        repo.update("acc_1", AccountPatch(status = AccountStatus.DELETED, deletedAt = now, purgeAfter = now.plus(Duration.ofDays(30))), now)
        assertFalse(repo.purge("acc_1", now.plus(Duration.ofDays(29))), "inside the grace nothing is purged")
        assertFalse(repo.restore("acc_1", AccountStatus.ACTIVE, now.plus(Duration.ofDays(31))), "after the grace nothing is restored")
        assertTrue(repo.restore("acc_1", AccountStatus.ACTIVE, now.plus(Duration.ofDays(29))))
        assertEquals(AccountStatus.ACTIVE, repo.findById("acc_1")!!.status)
        assertNull(repo.findById("acc_1")!!.purgeAfter)
    }

    @Test
    fun `two administrators demoting or suspending each other at once leave one - the guard locks the holders`() {
        repeat(12) { round ->
            AccountDb.clean()
            repo.insert(account("acc_a", "a@example.com", roles = setOf("USER", "ADMIN")), listOf(identity("idn_a", "acc_a", subject = "a@example.com")))
            repo.insert(account("acc_b", "b@example.com", roles = setOf("USER", "ADMIN")), listOf(identity("idn_b", "acc_b", subject = "b@example.com")))
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val futures = listOf("acc_a" to "acc_b", "acc_b" to "acc_a").map { (_, target) ->
                pool.submit<GuardedResult> {
                    go.await()
                    if (round % 2 == 0) repo.updateUnlessLast(target, AccountPatch(status = AccountStatus.SUSPENDED), now, "ADMIN") else repo.revokeRoleUnlessLast(target, "ADMIN", now)
                }
            }
            go.countDown()
            val results = futures.map { it.get() }
            pool.shutdown()
            assertEquals(1L, repo.countActiveWithRole("ADMIN"), "round $round: $results")
            assertEquals(1, results.count { it == GuardedResult.DONE }, results.toString())
        }
    }
}
