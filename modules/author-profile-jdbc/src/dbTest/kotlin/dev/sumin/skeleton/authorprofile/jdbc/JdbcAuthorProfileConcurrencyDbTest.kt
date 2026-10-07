package dev.sumin.skeleton.authorprofile.jdbc

import dev.sumin.skeleton.authorprofile.CapInsert
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.account
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.profile
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.repository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 같은 순간에 출발하는 진짜 스레드 — 유니크와 잠금이 판정을 가르는지 본다. */
class JdbcAuthorProfileConcurrencyDbTest {
    @Test
    fun `many threads creating the first profile of one account - exactly one wins`() {
        repeat(3) {
            val acc = account()
            val results = parallel(16) { repository.insert(profile(acc, primary = true)) }
            assertEquals(1, results.count { it }, "round $it")
            assertEquals(1, ProfileDb.rows(acc))
        }
    }

    @Test
    fun `many threads creating the same name and tag for different accounts - exactly one wins`() {
        repeat(3) {
            val key = "race${System.nanoTime()}"
            val results = parallel(16) { repository.insert(profile(account(), primary = true, key = key, tag = "0001")) }
            assertEquals(1, results.count { it }, "round $it")
        }
    }

    @Test
    fun `more threads than the cap never push an account past the cap`() {
        repeat(3) { round ->
            val acc = account()
            assertTrue(repository.insert(profile(acc, primary = true)))
            val cap = 4
            val results = parallel(20) { repository.insertWithinCap(profile(acc), cap, 0, null) }
            assertEquals(cap - 1, results.count { it == CapInsert.INSERTED }, "round $round: first profile + 3 = cap")
            assertEquals(20 - (cap - 1), results.count { it == CapInsert.LIMIT_REACHED })
            assertEquals(cap, repository.countActive(acc))
        }
    }

    @Test
    fun `more threads than the create limit never push an account past it - deleted rows count, rows before the window and the first profile do not`() {
        repeat(3) { round ->
            val acc = account()
            assertTrue(repository.insert(profile(acc, primary = true)))   // 첫 프로필은 세지 않는다
            val since = ProfileDb.T0.minusSeconds(3600)
            assertEquals(CapInsert.INSERTED, repository.insertWithinCap(profile(acc, at = since.minusSeconds(60)), 99, 3, since), "older than the window: not counted")
            val gone = profile(acc)
            assertEquals(CapInsert.INSERTED, repository.insertWithinCap(gone, 99, 3, since))
            repository.markDeleted(gone.id, acc, ProfileDb.T0)   // 지운 것도 센다
            val results = parallel(20) { repository.insertWithinCap(profile(acc), 99, 3, since) }
            assertEquals(2, results.count { it == CapInsert.INSERTED }, "round $round: 1 deleted + 2 = limit 3")
            assertEquals(18, results.count { it == CapInsert.RATE_LIMITED })
            assertEquals(3, repository.countCreatedSince(acc, since))
        }
    }

    @Test
    fun `the cap is checked before the create limit and no limit means unlimited`() {
        val acc = account()
        assertTrue(repository.insert(profile(acc, primary = true)))
        val since = ProfileDb.T0.minusSeconds(3600)
        assertEquals(CapInsert.INSERTED, repository.insertWithinCap(profile(acc), 2, 1, since))
        assertEquals(CapInsert.LIMIT_REACHED, repository.insertWithinCap(profile(acc), 2, 1, since), "cap first")
        assertEquals(CapInsert.RATE_LIMITED, repository.insertWithinCap(profile(acc), 9, 1, since))
        assertEquals(CapInsert.INSERTED, repository.insertWithinCap(profile(acc), 9, 0, since), "createLimit 0: no limit")
        assertEquals(CapInsert.INSERTED, repository.insertWithinCap(profile(acc), 9, 1, null), "no window: no limit")
    }

    @Test
    fun `the cap counts a deleted-then-recreated slot correctly under contention`() {
        val acc = account()
        repository.insert(profile(acc, primary = true))
        val extras = List(2) { profile(acc).also { p -> assertEquals(CapInsert.INSERTED, repository.insertWithinCap(p, 3, 0, null)) } }
        repository.markDeleted(extras[0].id, acc, ProfileDb.T0)   // 한 자리가 난다
        val results = parallel(10) { repository.insertWithinCap(profile(acc), 3, 0, null) }
        assertEquals(1, results.count { it == CapInsert.INSERTED })
        assertEquals(3, repository.countActive(acc))
    }

    @Test
    fun `the same name asked by many threads of one account inserts once - the rest see NAME_TAKEN or the cap`() {
        val acc = account()
        repository.insert(profile(acc, primary = true))
        val key = "same${System.nanoTime()}"
        val results = parallel(12) { repository.insertWithinCap(profile(acc, key = key, tag = "0001"), 5, 0, null) }
        assertEquals(1, results.count { it == CapInsert.INSERTED })
        assertEquals(11, results.count { it == CapInsert.NAME_TAKEN })
    }

    @Test
    fun `different accounts do not wait for or limit each other`() {
        val accounts = List(12) { account().also { a -> repository.insert(profile(a, primary = true)) } }
        val since = ProfileDb.T0.minusSeconds(3600)
        val results = parallel(12) { repository.insertWithinCap(profile(accounts[it]), 2, 3, since) }   // 계정마다 한도 3 — 서로의 줄을 세지 않는다
        assertTrue(results.all { it == CapInsert.INSERTED })
    }

    @Test
    fun `the cap is checked before a name clash - both applying answers LIMIT_REACHED`() {
        val key = "both${System.nanoTime()}"
        val holder = account()
        repository.insert(profile(holder, primary = true, key = key, tag = "0001"))
        val acc = account()
        repository.insert(profile(acc, primary = true))
        assertEquals(CapInsert.LIMIT_REACHED, repository.insertWithinCap(profile(acc, key = key, tag = "0001"), 1, 0, null))
        assertEquals(CapInsert.NAME_TAKEN, repository.insertWithinCap(profile(acc, key = key, tag = "0001"), 2, 0, null))
    }

    @Test
    fun `a deleted first profile still anchors the lock - and an account without any first profile row is an error`() {
        val acc = account()
        repository.insert(profile(acc, primary = true))
        repository.markDeleted(repository.findPrimary(acc)!!.id, acc, ProfileDb.T0)
        assertEquals(CapInsert.INSERTED, repository.insertWithinCap(profile(acc), 2, 0, null))
        assertFailsWith<IllegalStateException> { repository.insertWithinCap(profile(account()), 2, 0, null) }
    }

    @Test
    fun `different accounts racing for the same key and tag through insertWithinCap - exactly one wins and the rest see NAME_TAKEN`() {
        repeat(3) { round ->
            val key = "dup${System.nanoTime()}"
            val accounts = List(12) { account().also { a -> repository.insert(profile(a, primary = true)) } }
            val results = parallel(12) { repository.insertWithinCap(profile(accounts[it], key = key, tag = "0001"), 5, 3, ProfileDb.T0.minusSeconds(3600)) }
            assertEquals(1, results.count { it == CapInsert.INSERTED }, "round $round")
            assertEquals(11, results.count { it == CapInsert.NAME_TAKEN }, "round $round")
        }
    }

    @Test
    fun `an open transaction holding account A's anchor does not block account B`() {
        val a = account(); val b = account()
        repository.insert(profile(a, primary = true)); repository.insert(profile(b, primary = true))
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit {
                ProfileDb.tx.execute {
                    ProfileDb.jdbc.queryForList("select id from author_profiles where primary_of = :a for update", mapOf("a" to a), String::class.java)
                    holding.countDown()
                    release.await()
                }
            }
            holding.await()
            val other = pool.submit<CapInsert> { repository.insertWithinCap(profile(b), 3, 3, ProfileDb.T0.minusSeconds(3600)) }
            assertEquals(CapInsert.INSERTED, other.get(10, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            holder.get()
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `joined to an outer transaction that already read a snapshot, the cap still sees the latest commit`() {
        val acc = account()
        repository.insert(profile(acc, primary = true))
        ProfileDb.tx.execute {
            assertEquals(1, repository.countActive(acc))   // 바깥 트랜잭션이 읽기 스냅샷을 잡는다 (MySQL 반복 읽기)
            assertEquals(CapInsert.INSERTED, parallel(1) { repository.insertWithinCap(profile(acc), 2, 0, null) }.single())   // 다른 연결이 커밋한다
            assertEquals(CapInsert.LIMIT_REACHED, repository.insertWithinCap(profile(acc), 2, 0, null), "the cap counts with a locking read, not the stale snapshot")
            assertEquals(CapInsert.RATE_LIMITED, repository.insertWithinCap(profile(acc), 9, 1, ProfileDb.T0.minusSeconds(3600)), "so does the create limit - the row another connection committed counts")
        }
        assertEquals(2, repository.countActive(acc))
    }

    @Test
    fun `insertWithinCap called inside an outer transaction commits and rolls back with it`() {
        val acc = account()
        repository.insert(profile(acc, primary = true))
        val p = profile(acc)
        runCatching { ProfileDb.tx.execute { repository.insertWithinCap(p, 5, 0, null); error("boom") } }
        assertNull(repository.find(p.id))
        ProfileDb.tx.execute { assertEquals(CapInsert.INSERTED, repository.insertWithinCap(p, 5, 0, null)) }
        assertEquals(p, repository.find(p.id))
    }
}
