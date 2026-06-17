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
}
