package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountBlocks
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 두 DB 에서 — 재가입 차단 표는 해시만 담고, 운영자 지우기(forced)는 정지된 계정에만 듣고, 차단은 지운 뒤에도 남는다 */
class JdbcAccountBlockDbTest {
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")
    private val repo = AccountDb.accounts
    private val jdbc = AccountDb.jdbc
    private val blocks = AccountBlocks(AccountDb.blocks, "test-key-for-blocks".toByteArray(), Duration.ofDays(10), TimeProvider.fixed(now))

    @BeforeTest fun clean() = AccountDb.clean()

    private fun suspended(id: String = "acc_1", email: String = "ann@example.com", status: AccountStatus = AccountStatus.SUSPENDED): Account {
        val a = Account(id, email, true, status, setOf("USER"), "Ann Kim", "ko", null, now, now, suspendedReason = "abuse")
        assertTrue(repo.insert(a, listOf(Identity("idn_pw_$id", id, "password", email, true, "{bcrypt}h", null, now), Identity("idn_g_$id", id, "google", "google-subject-$id", true, null, null, now))))
        return a
    }

    @Test
    fun `a forced erase works on a suspended account only, a normal erase never touches it`() {
        suspended()
        assertFalse(repo.erase("acc_1", now), "not DELETED")
        assertTrue(repo.erase("acc_1", now, forced = true))
        assertEquals(AccountStatus.ERASED, repo.findById("acc_1")!!.status)
        assertFalse(repo.erase("acc_1", now, forced = true), "already erased")
        suspended("acc_2", "b@example.com", AccountStatus.ACTIVE)
        assertFalse(repo.erase("acc_2", now, forced = true), "an active account is not erasable by force")
        assertFalse(repo.purge("acc_2", now, forced = true))
    }

    @Test
    fun `a forced purge removes the suspended row`() {
        suspended()
        assertTrue(repo.purge("acc_1", now, forced = true))
        assertEquals(null, repo.findById("acc_1"))
    }

    @Test
    fun `the block table keeps hashes only, survives the erasure, honours expiry and removal`() {
        val a = suspended()
        blocks.add(a, repo.identitiesOf("acc_1"), "fraud", "acc_admin")
        assertTrue(repo.erase("acc_1", now, forced = true))

        assertEquals(2, AccountDb.blocks.list(0, 10).total, "the address (password identity = same hash) and the google subject")
        assertEquals(emptyList(), PlantedDataScan.find(jdbc, DbTestDatabase.vendor, listOf("ann@example.com", "Ann Kim", "google-subject-acc_1", "{bcrypt}h")),
            "no column of any table holds a personal value - the blocks hold HMACs")
        assertTrue(blocks.blocked("Ann@Example.com"), "normalised like every other address")
        assertTrue(blocks.blocked(null, "google", "google-subject-acc_1"))
        assertFalse(blocks.blocked("other@example.com", "google", "other"))

        val later = AccountBlocks(AccountDb.blocks, "test-key-for-blocks".toByteArray(), Duration.ofDays(10), TimeProvider.fixed(now.plus(Duration.ofDays(11))))
        assertFalse(later.blocked("ann@example.com"), "expired")
        assertEquals(2, later.sweep())
        assertEquals(0, AccountDb.blocks.list(0, 10).total)

        blocks.add(a, listOf(Identity("i", "acc_1", "google", "x", true, null, null, now)), null, null)
        blocks.add(a, listOf(Identity("i", "acc_1", "google", "x", true, null, null, now)), null, null)
        assertEquals(2, AccountDb.blocks.list(0, 10).total, "adding the same hash twice is one row")
        assertTrue(blocks.remove(AccountDb.blocks.list(0, 10).items.first().id))
        assertEquals(1, AccountDb.blocks.list(0, 10).total)
    }

    @Test
    fun `a different server key does not match - the hash is keyed`() {
        val a = suspended()
        blocks.add(a, emptyList(), null, null)
        assertFalse(AccountBlocks(AccountDb.blocks, "another-key".toByteArray(), Duration.ZERO, TimeProvider.fixed(now)).blocked("ann@example.com"))
    }
}
