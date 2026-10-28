package dev.sumin.skeleton.notification

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InMemoryNotificationInboxRepositoryTest {
    @Test
    fun `stores recipient notifications newest first and marks them read`() {
        val repository = InMemoryNotificationInboxRepository()
        val older = NotificationEvent(
            id = "event-1",
            topic = "demo",
            type = "older",
            message = "older message",
            createdAt = Instant.parse("2026-06-17T00:00:00Z"),
        )
        val newer = NotificationEvent(
            id = "event-2",
            topic = "demo",
            type = "newer",
            message = "newer message",
            createdAt = Instant.parse("2026-06-17T00:01:00Z"),
        )

        repository.save(older, setOf("acc_user"))
        repository.save(newer, setOf("acc_user", "acc_admin"))

        val page = repository.findByRecipient(
            recipientId = "acc_user",
            query = NotificationInboxQuery(page = 0, size = 10),
        )
        val readAt = Instant.parse("2026-06-17T00:02:00Z")
        val read = repository.markRead("acc_user", "event-2", readAt)
        val unread = repository.findByRecipient(
            recipientId = "acc_user",
            query = NotificationInboxQuery(page = 0, size = 10, unreadOnly = true),
        )

        assertEquals(2, page.totalElements)
        assertEquals(listOf("event-2", "event-1"), page.values.map { it.event.id })
        assertNotNull(read)
        assertEquals(readAt, read.readAt)
        assertEquals(listOf("event-1"), unread.values.map { it.event.id })
    }

    @Test
    fun `the inbox of the same account id is separate per project`() {
        fun project(id: String) = dev.sumin.skeleton.common.project.Project(id, listOf("$id.test"), id, dev.sumin.skeleton.common.project.ProjectDatasource(), emptyMap())
        val repository = InMemoryNotificationInboxRepository()
        val event = NotificationEvent(id = "event-1", topic = "demo", type = "t", createdAt = Instant.parse("2026-06-17T00:00:00Z"))
        dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { repository.save(event, setOf("acc_user")) }

        fun page(id: String) = dev.sumin.skeleton.common.project.ProjectContext.runIn(project(id)) { repository.findByRecipient("acc_user", NotificationInboxQuery()) }
        assertEquals(1, page("a").totalElements)
        assertEquals(0, page("b").totalElements)
        assertEquals(0, dev.sumin.skeleton.common.project.ProjectContext.runIn(project("b")) { repository.markAllRead("acc_user", Instant.now()) })
        assertEquals(1, dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { repository.markAllRead("acc_user", Instant.now()) })
    }

    private fun ev(id: String, key: String? = "k", at: String = "2026-06-17T00:00:00Z", title: String = id, topic: String = "demo") =
        NotificationEvent(id = id, topic = topic, type = "t", title = title, collapseKey = key, link = "/n/$id", createdAt = Instant.parse(at))

    @Test
    fun `an unread event with the same collapse key folds into the existing row`() {
        val repository = InMemoryNotificationInboxRepository()
        repository.save(ev("e1", at = "2026-06-17T00:00:00Z"), setOf("acc_user"))
        val saved = repository.save(ev("e2", at = "2026-06-17T00:05:00Z"), setOf("acc_user"))

        val page = repository.findByRecipient("acc_user")
        val row = page.values.single()
        assertEquals(1, page.totalElements)
        assertEquals("e1", row.event.id)                     // the read address stays the first one
        assertEquals("e2", row.event.title)                  // content is the newest
        assertEquals("/n/e2", row.event.link)
        assertEquals(Instant.parse("2026-06-17T00:05:00Z"), row.event.createdAt)
        assertEquals(2, row.collapsedCount)
        assertEquals(row, saved.single())
    }

    @Test
    fun `after the row is read the same collapse key starts a new row`() {
        val repository = InMemoryNotificationInboxRepository()
        repository.save(ev("e1"), setOf("acc_user"))
        repository.markRead("acc_user", "e1", Instant.parse("2026-06-17T00:01:00Z"))
        repository.save(ev("e2", at = "2026-06-17T00:02:00Z"), setOf("acc_user"))

        val page = repository.findByRecipient("acc_user")
        assertEquals(listOf("e2", "e1"), page.values.map { it.event.id })
        assertEquals(listOf(1, 1), page.values.map { it.collapsedCount })
    }

    @Test
    fun `publishing the same event id again does not raise the count`() {
        val repository = InMemoryNotificationInboxRepository()
        repository.save(ev("e1"), setOf("acc_user"))
        repository.save(ev("e1"), setOf("acc_user"))

        assertEquals(1, repository.findByRecipient("acc_user").values.single().collapsedCount)
    }

    @Test
    fun `different keys and null keys never fold, and recipients fold separately`() {
        val repository = InMemoryNotificationInboxRepository()
        repository.save(ev("a1", key = "a"), setOf("acc_user", "acc_other"))
        repository.save(ev("b1", key = "b"), setOf("acc_user"))
        repository.save(ev("n1", key = null), setOf("acc_user"))
        repository.save(ev("n2", key = null), setOf("acc_user"))
        repository.save(ev("a2", key = "a"), setOf("acc_user"))

        assertEquals(4, repository.findByRecipient("acc_user").totalElements)
        assertEquals(2, repository.findByRecipient("acc_user").values.single { it.event.id == "a1" }.collapsedCount)
        assertEquals(1, repository.findByRecipient("acc_other").values.single().collapsedCount)
    }

    @Test
    fun `the same collapse key in another project does not fold`() {
        fun project(id: String) = dev.sumin.skeleton.common.project.Project(id, listOf("$id.test"), id, dev.sumin.skeleton.common.project.ProjectDatasource(), emptyMap())
        val repository = InMemoryNotificationInboxRepository()
        dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { repository.save(ev("e1"), setOf("acc_user")) }
        dev.sumin.skeleton.common.project.ProjectContext.runIn(project("b")) { repository.save(ev("e2"), setOf("acc_user")) }

        assertEquals(1, dev.sumin.skeleton.common.project.ProjectContext.runIn(project("a")) { repository.findByRecipient("acc_user").values.single().collapsedCount })
        assertEquals(1, dev.sumin.skeleton.common.project.ProjectContext.runIn(project("b")) { repository.findByRecipient("acc_user").values.single().collapsedCount })
    }

    @Test
    fun `two threads folding the same key at once end with one row of count n`() {
        val repository = InMemoryNotificationInboxRepository()
        val threads = 8
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map { i -> pool.submit { start.await(); repository.save(ev("e$i"), setOf("acc_user")) } }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val page = repository.findByRecipient("acc_user")
        assertEquals(1, page.totalElements)
        assertEquals(threads, page.values.single().collapsedCount)
    }

    @Test
    fun `countUnread counts unread rows`() {
        val repository = InMemoryNotificationInboxRepository()
        repository.save(ev("e1", key = null), setOf("acc_user"))
        repository.save(ev("e2", key = null), setOf("acc_user"))
        repository.save(ev("e3", key = "k"), setOf("acc_user"))
        repository.save(ev("e4", key = "k"), setOf("acc_user"))
        assertEquals(3L, repository.countUnread("acc_user"))
        repository.markRead("acc_user", "e1")
        assertEquals(2L, repository.countUnread("acc_user"))
        assertEquals(0L, repository.countUnread("acc_nobody"))
    }

    @Test
    fun `deleteOlderThan removes rows strictly older than the cutoff regardless of read state`() {
        val repository = InMemoryNotificationInboxRepository()
        repository.save(ev("old", key = null, at = "2026-06-01T00:00:00Z"), setOf("acc_user"))
        repository.save(ev("edge", key = null, at = "2026-06-10T00:00:00Z"), setOf("acc_user"))
        repository.save(ev("new", key = null, at = "2026-06-20T00:00:00Z"), setOf("acc_user"))
        repository.markRead("acc_user", "old")

        assertEquals(1, repository.deleteOlderThan(Instant.parse("2026-06-10T00:00:00Z")))
        assertEquals(setOf("edge", "new"), repository.findByRecipient("acc_user").values.map { it.event.id }.toSet())
    }

    @Test
    fun `deleteByTopic and deleteByRecipientAndTopic`() {
        val repository = InMemoryNotificationInboxRepository()
        repository.save(ev("a", key = null, topic = "x"), setOf("acc_1", "acc_2"))
        repository.save(ev("b", key = null, topic = "y"), setOf("acc_1", "acc_2"))

        assertEquals(1, repository.deleteByRecipientAndTopic("acc_1", "x"))
        assertEquals(0, repository.deleteByRecipientAndTopic("acc_1", "x"))
        assertEquals(1, repository.findByRecipient("acc_2", NotificationInboxQuery(topic = "x")).totalElements)
        assertEquals(3, repository.deleteByTopic("x").let { it + repository.deleteByTopic("y") })
        assertEquals(0, repository.findByRecipient("acc_2").totalElements)
    }
}
