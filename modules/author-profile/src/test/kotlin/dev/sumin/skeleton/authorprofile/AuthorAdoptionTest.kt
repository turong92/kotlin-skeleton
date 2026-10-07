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
    /**
     * 옛 행을 가진 계정과 그 행 수. [fail] 의 계정은 adopt 가 던지고, [stuck] 의 계정은 adopt 가 늘 0 을 돌려준다(행이 남는다).
     * 커서 계약을 지킨다: 계정 id 오름차순, [after] 보다 큰 것만 — [ignoreCursor] 면 일부러 어긴다
     */
    private class FakeListener(
        override val name: String,
        rows: Map<String, Int>,
        private val fail: Set<String> = emptySet(),
        private val stuck: Set<String> = emptySet(),
        private val failPending: Boolean = false,
        private val ignoreCursor: Boolean = false,
        private val order: Comparator<String> = naturalOrder(),
    ) : AuthorAdoptionListener {
        val pending = LinkedHashMap(rows)
        val adopted = LinkedHashMap<String, String>()
        val cursors = mutableListOf<String?>()
        var adoptCalls = 0

        override fun pendingAccounts(limit: Int, after: String?): List<String> {
            cursors += after
            if (failPending) error("pending failed for acc_secret")
            val ordered = pending.filterValues { it > 0 }.keys.sortedWith(order)
            return ordered.filter { ignoreCursor || after == null || order.compare(it, after) > 0 }.take(limit)
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

    private fun harnessWithAccounts(vararg ids: String): Harness =
        Harness().also { h -> ids.forEachIndexed { i, id -> h.account(id, "Name-$id", (i + 1).toString().padStart(4, '0')) } }

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
        assertEquals(listOf(null, "acc_2", "acc_4", "acc_5"), listener.cursors, "the cursor walks forward batch by batch")
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
    fun `accounts that never move do not starve the healthy ones behind them, even with a batch of one`() {
        val h = harnessWithAccounts("acc_a", "acc_b", "acc_c", "acc_d")
        val listener = FakeListener("board", mapOf("acc_a" to 3, "acc_b" to 3, "acc_c" to 1, "acc_d" to 2), stuck = setOf("acc_a", "acc_b"), fail = emptySet())

        val moved = AuthorAdoption({ listOf(listener) }, h.service, batchSize = 1).run()

        assertEquals(3, moved, "c and d moved although a and b are stuck in front of them")
        assertEquals(setOf("acc_c", "acc_d"), listener.adopted.keys)
        assertEquals(listOf(null, "acc_a", "acc_b", "acc_c", "acc_d"), listener.cursors, "every account is looked at once and the run ends")
    }

    @Test
    fun `a failing account is skipped and the ones behind it move - it stays pending for the next start`() {
        val h = harnessWithAccounts("acc_bad", "acc_1", "acc_2")
        val listener = FakeListener("board", mapOf("acc_bad" to 1, "acc_1" to 1, "acc_2" to 1), fail = setOf("acc_bad"))

        assertEquals(2, AuthorAdoption({ listOf(listener) }, h.service, batchSize = 1).run())

        assertEquals(setOf("acc_1", "acc_2"), listener.adopted.keys)
        assertEquals(listOf("acc_bad"), listener.pending.filterValues { it > 0 }.keys.toList())
    }

    @Test
    fun `an account the service cannot give a profile is skipped like any other failure`() {
        val h = Harness(autoAccounts = false).also { it.account("acc_1", "Name", "0001") }   // acc_ghost 는 계정이 없다 → NotFound
        val listener = FakeListener("board", mapOf("acc_ghost" to 1, "acc_1" to 1))
        assertEquals(1, AuthorAdoption({ listOf(listener) }, h.service, batchSize = 1).run())
        assertEquals(setOf("acc_1"), listener.adopted.keys)
    }

    @Test
    fun `a listener that ignores the cursor is stopped instead of looping forever`() {
        val h = harnessWithAccounts("acc_1")
        val listener = FakeListener("board", mapOf("acc_1" to 1), stuck = setOf("acc_1"), ignoreCursor = true)
        assertEquals(0, AuthorAdoption({ listOf(listener) }, h.service, batchSize = 1).run())
        assertEquals(2, listener.cursors.size, "the second look returned an account that was not past the cursor - stop")
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
    fun `a failure to record adopted_at does not undo or hide the move`() {
        val h = harnessWithAccounts("acc_1")
        val broken = object : AuthorProfileRepository by h.repository {
            override fun markAdopted(accountId: String, now: java.time.Instant): Boolean = error("no write for $accountId")
        }
        val service = AuthorProfileService(broken, { h.accounts }, h.names, h.limits, h.properties, h.time)
        val listener = FakeListener("board", mapOf("acc_1" to 2))
        assertEquals(2, AuthorAdoption({ listOf(listener) }, service).run())
        assertEquals(setOf("acc_1"), listener.adopted.keys)
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

    @Test
    fun `accounts that are not ACTIVE are skipped - no first profile row, no adopt call`() {
        val h = Harness()
        h.account("acc_1", "A", "0001")
        h.account("acc_2", "B", "0002", status = dev.sumin.skeleton.account.AccountStatus.SUSPENDED)
        h.account("acc_3", "C", "0003", status = dev.sumin.skeleton.account.AccountStatus.DELETED)
        h.account("acc_4", "D", "0004", status = dev.sumin.skeleton.account.AccountStatus.ERASED)
        val listener = FakeListener("board", mapOf("acc_1" to 1, "acc_2" to 1, "acc_3" to 1, "acc_4" to 1))

        val moved = AuthorAdoption({ listOf(listener) }, h.service, batchSize = 10).run()

        assertEquals(1, moved)
        assertEquals(setOf("acc_1"), listener.adopted.keys)
        assertEquals(1, listener.adoptCalls)
        assertEquals(setOf("acc_1"), h.repository.all().map { it.accountId }.toSet())
    }

    @Test
    fun `a list call marks adopted_at even when the listener moved nothing, so the next list does not call the listener again - and a failing listener keeps it open`() {
        val h = harnessWithAccounts("acc_1")
        val quiet = FakeListener("board", emptyMap())
        h.adoptionListeners += quiet
        h.service.ensureFirstProfile("acc_1")
        assertNotNull(h.repository.findPrimary("acc_1")!!.adoptedAt, "0 rows is still done")
        val calls = quiet.adoptCalls
        h.service.ensureFirstProfile("acc_1")
        assertEquals(calls, quiet.adoptCalls, "no board UPDATE on every GET for an account with nothing to adopt")

        val h2 = harnessWithAccounts("acc_2")
        val failing = FakeListener("board", mapOf("acc_2" to 1), fail = setOf("acc_2"))
        h2.adoptionListeners += failing
        h2.service.ensureFirstProfile("acc_2")
        assertEquals(null, h2.repository.findPrimary("acc_2")!!.adoptedAt, "a listener that threw leaves it open for the next call")
    }

    @Test
    fun `a failing activity lookup of one account is swallowed like any per-account failure`() {
        val h = harnessWithAccounts("acc_1", "acc_2", "acc_3")
        val flaky = object : dev.sumin.skeleton.account.AccountRepository by h.accounts {
            override fun findById(id: String) = if (id == "acc_2") error("lookup failed for $id") else h.accounts.findById(id)
        }
        val service = AuthorProfileService(h.repository, { flaky }, h.names, h.limits, h.properties, h.time)
        val listener = FakeListener("board", mapOf("acc_1" to 1, "acc_2" to 1, "acc_3" to 1))
        assertEquals(2, AuthorAdoption({ listOf(listener) }, service).run(), "acc_1 and acc_3 moved; the run did not stop at acc_2")
        assertEquals(setOf("acc_1", "acc_3"), listener.adopted.keys)
    }

    @Test
    fun `the cursor is the last returned account - a store that orders case-insensitively moves all three with a batch of one`() {
        val h = harnessWithAccounts("acc_a", "acc_B", "acc_c")
        val listener = FakeListener("board", mapOf("acc_a" to 1, "acc_B" to 1, "acc_c" to 1), order = String.CASE_INSENSITIVE_ORDER)
        assertEquals(3, AuthorAdoption({ listOf(listener) }, h.service, batchSize = 1).run(), "'acc_B' < 'acc_a' in Kotlin's order, but the store's order decides the walk")
        assertEquals(setOf("acc_a", "acc_B", "acc_c"), listener.adopted.keys)
    }

    @Test
    fun `a listener that alternates between accounts it already returned is stopped by the seen set`() {
        val h = harnessWithAccounts("acc_1", "acc_2")
        val cycling = object : dev.sumin.skeleton.common.author.AuthorAdoptionListener {
            override val name = "cycling"
            var calls = 0
            override fun pendingAccounts(limit: Int, after: String?): List<String> = listOf(if (calls++ % 2 == 0) "acc_1" else "acc_2").let { if (calls > 2) listOf("acc_1") else it }
            override fun adopt(accountId: String, authorId: String) = 0
        }
        AuthorAdoption({ listOf(cycling) }, h.service, batchSize = 1).run()
        assertEquals(3, cycling.calls, "acc_1, acc_2, then acc_1 again: nothing new, so it stops instead of looping")
    }
}
