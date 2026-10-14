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
}
