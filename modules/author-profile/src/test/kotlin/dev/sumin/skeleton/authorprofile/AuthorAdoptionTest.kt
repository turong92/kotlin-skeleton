package dev.sumin.skeleton.authorprofile

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.author.AuthorAdoptionListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class AuthorAdoptionTest {
    /** 옛 행을 가진 계정과 그 행 수. [fail] 의 계정은 adopt 가 던지고, [stuck] 의 계정은 adopt 가 늘 0 을 돌려준다(행이 남는다) */
    private class FakeListener(
        override val name: String,
        rows: Map<String, Int>,
        private val fail: Set<String> = emptySet(),
        private val stuck: Set<String> = emptySet(),
        private val failPending: Boolean = false,
    ) : AuthorAdoptionListener {
        val pending = LinkedHashMap(rows)
        val adopted = LinkedHashMap<String, String>()
        var pendingCalls = 0
        var adoptCalls = 0

        override fun pendingAccounts(limit: Int): List<String> {
            pendingCalls++
            if (failPending) error("pending failed for acc_secret")
            return pending.filterValues { it > 0 }.keys.take(limit)
        }

        override fun adopt(accountId: String, authorId: String): Int {
            adoptCalls++
            if (accountId in fail) error("adopt failed for $accountId / $authorId")
            if (accountId in stuck) return 0
            val n = pending[accountId] ?: 0
            if (n > 0) adopted[accountId] = authorId
            pending[accountId] = 0
            return n
        }
    }

    private fun harnessWithAccounts(vararg ids: String): Harness = Harness().also { h -> ids.forEachIndexed { i, id -> h.account(id, "Name-$id", (i + 1).toString().padStart(4, '0')) } }

    @Test
    fun `every pending account gets its first profile and its old rows are moved to it, over several batches`() {
        val h = harnessWithAccounts("acc_1", "acc_2", "acc_3", "acc_4", "acc_5")
        val listener = FakeListener("board", mapOf("acc_1" to 2, "acc_2" to 1, "acc_3" to 4, "acc_4" to 1, "acc_5" to 3))

        val moved = AuthorAdoption({ listOf(listener) }, h.service, batchSize = 2).run()

        assertEquals(11, moved)
        assertEquals(setOf("acc_1", "acc_2", "acc_3", "acc_4", "acc_5"), listener.adopted.keys)
        listener.adopted.forEach { (account, profileId) ->
            val first = h.repository.findPrimary(account)!!
            assertEquals(first.id, profileId)
            assertEquals("Name-$account", first.name, "the account name is copied once")
            assertNotNull(first.adoptedAt, "adopted_at is filled")
        }
        assertEquals(5, h.repository.all().size)
    }

    @Test
    fun `running again changes nothing - no new profile, no second move`() {
        val h = harnessWithAccounts("acc_1", "acc_2")
        val listener = FakeListener("board", mapOf("acc_1" to 1, "acc_2" to 1))
        val adoption = AuthorAdoption({ listOf(listener) }, h.service)
        adoption.run()
        val before = h.repository.all()
        val adoptCalls = listener.adoptCalls

        assertEquals(0, adoption.run())

        assertEquals(before, h.repository.all())
        assertEquals(adoptCalls, listener.adoptCalls, "nothing pending - adopt is not called again")
    }

    @Test
    fun `a round without progress ends the run - an account that never moves does not loop forever or starve the rest`() {
        val h = harnessWithAccounts("acc_stuck")
        val listener = FakeListener("board", mapOf("acc_stuck" to 3), stuck = setOf("acc_stuck"))
        assertEquals(0, AuthorAdoption({ listOf(listener) }, h.service).run())
        assertEquals(1, listener.pendingCalls, "one look, no progress, stop")
        assertEquals(1, listener.adoptCalls)
    }

    @Test
    fun `a failing account is skipped, the others move, and the failing one is left for the next start`() {
        val h = harnessWithAccounts("acc_bad", "acc_1", "acc_2")
        val listener = FakeListener("board", mapOf("acc_bad" to 1, "acc_1" to 1, "acc_2" to 1), fail = setOf("acc_bad"))

        val moved = AuthorAdoption({ listOf(listener) }, h.service, batchSize = 1).run()

        // 배치가 1 이라 첫 바퀴는 acc_bad 만 본다: 진행 0 이면 멈춘다 — 굶기지 않으려면 배치를 키우는 것이 운영 몫이다. 여기서는 멈춤 계약을 확인한다
        assertEquals(0, moved)
        assertTrue(listener.adopted.isEmpty())

        val moved2 = AuthorAdoption({ listOf(listener) }, h.service, batchSize = 10).run()
        assertEquals(2, moved2, "with room in the batch the healthy accounts move")
        assertEquals(setOf("acc_1", "acc_2"), listener.adopted.keys)
        assertEquals(1, listener.pending.filterValues { it > 0 }.size, "only the failing account is still pending")
    }

    @Test
    fun `an account the service cannot give a profile is skipped like any other failure`() {
        val h = harnessWithAccounts("acc_1")   // acc_ghost 는 계정이 없다 → ensureFirstProfile 이 NotFound
        val listener = FakeListener("board", mapOf("acc_ghost" to 1, "acc_1" to 1))
        assertEquals(1, AuthorAdoption({ listOf(listener) }, h.service).run())
        assertEquals(setOf("acc_1"), listener.adopted.keys)
    }

    @Test
    fun `a listener that throws does not stop the next listener`() {
        val h = harnessWithAccounts("acc_1", "acc_2")
        val broken = FakeListener("broken", emptyMap(), failPending = true)
        val good = FakeListener("good", mapOf("acc_1" to 1, "acc_2" to 2))
        assertEquals(3, AuthorAdoption({ listOf(broken, good) }, h.service).run())
        assertEquals(setOf("acc_1", "acc_2"), good.adopted.keys)
    }

    @Test
    fun `several listeners move their rows to the same first profile`() {
        val h = harnessWithAccounts("acc_1")
        val posts = FakeListener("board", mapOf("acc_1" to 1))
        val other = FakeListener("other", mapOf("acc_1" to 5))
        assertEquals(6, AuthorAdoption({ listOf(posts, other) }, h.service).run())
        assertEquals(posts.adopted["acc_1"], other.adopted["acc_1"])
        assertEquals(1, h.repository.all().size)
    }

    @Test
    fun `no listeners is a quiet no-op`() {
        val h = Harness()
        assertEquals(0, AuthorAdoption({ emptyList() }, h.service).run())
        assertTrue(h.repository.all().isEmpty())
    }

    @Test
    fun `an account without a name gets a nameless first profile and its rows move there`() {
        val h = Harness().also { it.account("acc_1") }
        val listener = FakeListener("board", mapOf("acc_1" to 2))
        assertEquals(2, AuthorAdoption({ listOf(listener) }, h.service).run())
        assertEquals(null, h.repository.findPrimary("acc_1")!!.name)
    }

    @Test
    fun `the normal log never carries an account id or a profile id`() {
        val logger = LoggerFactory.getLogger(AuthorAdoption::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = ch.qos.logback.classic.Level.DEBUG
        try {
            val h = harnessWithAccounts("acc_bad", "acc_1")
            val listener = FakeListener("board", mapOf("acc_bad" to 1, "acc_1" to 1), fail = setOf("acc_bad"))
            AuthorAdoption({ listOf(listener, FakeListener("broken", emptyMap(), failPending = true)) }, h.service).run()

            assertTrue(appender.list.isNotEmpty(), "the run says something")
            val text = appender.list.joinToString("\n") { e ->
                e.formattedMessage + " " + generateSequence(e.throwableProxy) { it.cause }.joinToString(" ") { it.className + it.message }
            }
            assertTrue("acc_" !in text && "prf_" !in text, text)
            assertTrue(appender.list.none { it.throwableProxy != null }, "no stack trace with messages that may carry ids")
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
    }
}
