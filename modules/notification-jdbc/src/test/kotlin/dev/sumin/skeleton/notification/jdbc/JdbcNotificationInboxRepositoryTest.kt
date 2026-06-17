package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JacksonJsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxQuery
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class JdbcNotificationInboxRepositoryTest {
    @Test
    fun `persists notifications and read state through jdbc`() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .build()
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(notificationInboxSchemaSql())
        val repository = JdbcNotificationInboxRepository(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            jsonCodec = JacksonJsonCodec(),
        )
        val event = NotificationEvent(
            id = "event-1",
            topic = "demo",
            type = "created",
            recipientIds = setOf("acc_user"),
            title = "Hello",
            message = "Stored notification",
            payload = mapOf("source" to "test", "count" to 1),
            createdAt = Instant.parse("2026-06-17T00:00:00Z"),
        )

        repository.save(event, setOf("acc_user", "acc_admin"))
        val page = repository.findByRecipient("acc_user", NotificationInboxQuery(page = 0, size = 10))
        val readAt = Instant.parse("2026-06-17T00:01:00Z")
        val read = repository.markRead("acc_user", "event-1", readAt)
        val unread = repository.findByRecipient(
            "acc_user",
            NotificationInboxQuery(page = 0, size = 10, unreadOnly = true),
        )

        assertEquals(1, page.totalElements)
        assertEquals("event-1", page.values.single().event.id)
        assertEquals(setOf("acc_user"), page.values.single().event.recipientIds)
        assertEquals("test", page.values.single().event.payload["source"])
        assertEquals(1, page.values.single().event.payload["count"])
        assertNotNull(read)
        assertEquals(readAt, read.readAt)
        assertEquals(0, unread.totalElements)
    }

    private fun notificationInboxSchemaSql(): String =
        """
        create table skeleton_notification_inbox (
            recipient_id varchar(128) not null,
            event_id varchar(128) not null,
            topic varchar(128) not null,
            type varchar(128) not null,
            severity varchar(32) not null,
            title varchar(255),
            message varchar(2000),
            payload_json clob not null,
            recipient_ids_json clob not null,
            event_created_at timestamp not null,
            read_at timestamp,
            created_at timestamp not null,
            updated_at timestamp not null,
            primary key (recipient_id, event_id)
        )
        """.trimIndent()
}
