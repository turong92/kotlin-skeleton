package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.SetNameResult
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 닉네임 키 · 꼬리표의 (키, 꼬리표) 유니크가 PostgreSQL · MySQL 에서 같게 동작한다 — NULL 은 서로 겹치지 않는다 */
class JdbcDisplayNameDbTest {
    private val repo = AccountDb.accounts
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")

    @BeforeTest fun clean() = AccountDb.clean()

    private fun acc(id: String, key: String? = "ann", tag: String? = null, name: String? = "Ann") =
        Account(id, "$id@example.com", true, AccountStatus.ACTIVE, setOf("USER"), name, null, null, now, now, displayNameKey = key, displayTag = tag)

    @Test
    fun `key and tag round-trip, and the tag column keeps its leading zeros`() {
        assertTrue(repo.insert(acc("acc_1", tag = "0042"), emptyList()))
        val back = repo.findById("acc_1")!!
        assertEquals("ann", back.displayNameKey); assertEquals("0042", back.displayTag)
    }

    @Test
    fun `any number of accounts may share a key while their tag is NULL - the NONE mode`() {
        assertTrue(repo.insert(acc("acc_1"), emptyList()))
        assertTrue(repo.insert(acc("acc_2"), emptyList()))
        assertTrue(repo.insert(acc("acc_3", key = null, name = null), emptyList()), "no key at all is fine too")
        assertTrue(repo.insert(acc("acc_4", key = null, name = null), emptyList()))
    }

    @Test
    fun `the same key and tag twice is refused by the unique index - both the UNIQUE mode and a tag clash`() {
        assertTrue(repo.insert(acc("acc_1", tag = "0000"), emptyList()))
        assertFalse(repo.insert(acc("acc_2", tag = "0000"), emptyList()))
        assertTrue(repo.insert(acc("acc_3", tag = "0001"), emptyList()))
        assertFalse(repo.insert(acc("acc_4", tag = "0001"), emptyList()))
        assertNull(repo.findById("acc_4"))
        assertTrue(repo.insert(acc("acc_5", key = "bob", tag = "0001"), emptyList()), "another key may use the same tag")
    }

    @Test
    fun `setDisplayName writes name key and tag together, reports a clash and changes nothing then`() {
        repo.insert(acc("acc_1", tag = "0007"), emptyList())
        repo.insert(acc("acc_2", key = "bob", tag = "0007", name = "Bob"), emptyList())
        assertEquals(SetNameResult.TAKEN, repo.setDisplayName("acc_2", "Ann", "ann", "0007", now))
        assertEquals("Bob", repo.findById("acc_2")!!.displayName)
        assertEquals(SetNameResult.DONE, repo.setDisplayName("acc_2", "Ann", "ann", "0008", now))
        assertEquals("ann" to "0008", repo.findById("acc_2")!!.let { it.displayNameKey to it.displayTag })
        assertEquals(SetNameResult.DONE, repo.setDisplayName("acc_2", "ANN", "ann", "0008", now), "writing your own pair again is not a clash")
        assertEquals(SetNameResult.DONE, repo.setDisplayName("acc_2", "Ann", "ann", null, now), "a NULL tag clears it")
        assertNull(repo.findById("acc_2")!!.displayTag)
        assertEquals(SetNameResult.NOT_FOUND, repo.setDisplayName("acc_nope", "Ann", "ann", null, now))
    }

    @Test
    fun `an erased row is never written and erasing frees the pair`() {
        repo.insert(acc("acc_1", tag = "0000"), emptyList())
        repo.update("acc_1", dev.sumin.skeleton.account.AccountPatch(status = AccountStatus.DELETED, deletedAt = now, purgeAfter = now), now)
        assertTrue(repo.erase("acc_1", now))
        val row = repo.findById("acc_1")!!
        assertNull(row.displayName); assertNull(row.displayNameKey); assertNull(row.displayTag)
        assertEquals(SetNameResult.NOT_FOUND, repo.setDisplayName("acc_1", "Mallory", "mallory", "0001", now))
        assertTrue(repo.insert(acc("acc_2", tag = "0000"), emptyList()), "the erased account released its pair")
    }

    @Test
    fun `displayTagsOf lists the tags a key uses and namesOf is one batched lookup that skips unknown ids`() {
        repo.insert(acc("acc_1", tag = "0003"), emptyList())
        repo.insert(acc("acc_2", tag = "0009"), emptyList())
        repo.insert(acc("acc_3", key = "bob", tag = "0004", name = "Bob"), emptyList())
        assertEquals(setOf("0003", "0009"), repo.displayTagsOf("ann"))
        assertEquals(emptySet(), repo.displayTagsOf("nobody"))
        val names = repo.namesOf(listOf("acc_1", "acc_3", "acc_nope"))
        assertEquals(setOf("acc_1", "acc_3"), names.keys)
        assertEquals("Bob", names.getValue("acc_3").displayName); assertEquals("0004", names.getValue("acc_3").displayTag)
        assertEquals(AccountStatus.ACTIVE, names.getValue("acc_1").status)
        assertTrue(repo.namesOf(emptyList()).isEmpty())
    }

    @Test
    fun `namesOf also works for more ids than one statement carries`() {
        val ids = (1..2500).map { "acc_big$it" }
        ids.take(3).forEach { repo.insert(acc(it, key = it, name = it), emptyList()) }
        assertEquals(ids.take(3).toSet(), repo.namesOf(ids).keys)
    }

    // ---- real concurrency on both databases (a MySQL deadlock between two inserts of the same key and tag used to be a 500)

    /** the TAGGED loop of the service in miniature: draw a tag from a SMALL pool so the threads collide a lot, retry on the clash */
    private fun placeTagged(id: String, key: String, pool: Int, rnd: java.util.Random): String {
        while (true) {
            val tag = "%04d".format(1 + rnd.nextInt(pool))
            if (repo.insert(acc(id, key = key, tag = tag, name = "Ann"), emptyList())) return tag
        }
    }

    @Test
    fun `thirty-two threads placing the same nickname with tags from a small pool all succeed with different tags, and none of them sees an exception`() {
        val threads = 32
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val go = java.util.concurrent.CountDownLatch(1)
            val jobs = (1..threads).map { n -> pool.submit<String> { go.await(); placeTagged("acc_t$n", "ann", pool = 40, rnd = java.util.Random(n.toLong())) } }
            go.countDown()
            val tags = jobs.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(threads, tags.toSet().size, "every account got its own tag: $tags")
            assertEquals(threads, repo.displayTagsOf("ann").size)
        } finally { pool.shutdown() }
    }

    @Test
    fun `renaming many accounts onto the same key at once never throws, every winner holds a different tag`() {
        val threads = 24
        (1..threads).forEach { assertTrue(repo.insert(acc("acc_r$it", key = "own$it", tag = "0001", name = "Own$it"), emptyList())) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val go = java.util.concurrent.CountDownLatch(1)
            val jobs = (1..threads).map { n ->
                pool.submit<String> {
                    go.await()
                    val rnd = java.util.Random(n.toLong())
                    while (true) {
                        val tag = "%04d".format(1 + rnd.nextInt(30))
                        if (repo.setDisplayName("acc_r$n", "Ann", "ann", tag, now) == SetNameResult.DONE) return@submit tag
                    }
                    @Suppress("UNREACHABLE_CODE") ""
                }
            }
            go.countDown()
            val tags = jobs.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(threads, tags.toSet().size, tags.toString())
        } finally { pool.shutdown() }
    }
}
