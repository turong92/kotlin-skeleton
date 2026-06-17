package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxPage
import dev.sumin.skeleton.notification.NotificationInboxQuery
import dev.sumin.skeleton.notification.NotificationInboxRecord
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.notification.NotificationSeverity
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class JdbcNotificationInboxRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val jsonCodec: JsonCodec,
) : NotificationInboxRepository {
    override fun save(
        event: NotificationEvent,
        recipientIds: Set<String>,
    ): List<NotificationInboxRecord> =
        recipientIds.mapNotNull { recipientId ->
            val normalizedRecipientId = recipientId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            insertIfAbsent(event, normalizedRecipientId)
            findOne(normalizedRecipientId, event.id)
        }

    override fun findByRecipient(
        recipientId: String,
        query: NotificationInboxQuery,
    ): NotificationInboxPage {
        val where = whereClause(query)
        val parameters = MapSqlParameterSource()
            .addValue("recipientId", recipientId.trim())
            .addValue("topic", query.topic?.trim())
            .addValue("limit", query.size)
            .addValue("offset", query.offset())

        val total = jdbc.queryForObject(
            "select count(*) from skeleton_notification_inbox $where",
            parameters,
            Long::class.java,
        ) ?: 0L
        val values = jdbc.query(
            """
            select *
            from skeleton_notification_inbox
            $where
            order by event_created_at desc, event_id desc
            limit :limit offset :offset
            """.trimIndent(),
            parameters,
        ) { rs, _ -> rs.toRecord() }

        return NotificationInboxPage(values = values, totalElements = total)
    }

    override fun markRead(
        recipientId: String,
        eventId: String,
        readAt: Instant,
    ): NotificationInboxRecord? {
        jdbc.update(
            """
            update skeleton_notification_inbox
            set read_at = coalesce(read_at, :readAt),
                updated_at = :readAt
            where recipient_id = :recipientId
              and event_id = :eventId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("recipientId", recipientId.trim())
                .addValue("eventId", eventId.trim())
                .addValue("readAt", readAt.toTimestamp()),
        )
        return findOne(recipientId.trim(), eventId.trim())
    }

    override fun markAllRead(
        recipientId: String,
        readAt: Instant,
    ): Int =
        jdbc.update(
            """
            update skeleton_notification_inbox
            set read_at = :readAt,
                updated_at = :readAt
            where recipient_id = :recipientId
              and read_at is null
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("recipientId", recipientId.trim())
                .addValue("readAt", readAt.toTimestamp()),
        )

    private fun insertIfAbsent(
        event: NotificationEvent,
        recipientId: String,
    ) {
        val now = Instant.now()
        runCatching {
            jdbc.update(
                """
                insert into skeleton_notification_inbox (
                    recipient_id,
                    event_id,
                    topic,
                    type,
                    severity,
                    title,
                    message,
                    payload_json,
                    recipient_ids_json,
                    event_created_at,
                    read_at,
                    created_at,
                    updated_at
                ) values (
                    :recipientId,
                    :eventId,
                    :topic,
                    :type,
                    :severity,
                    :title,
                    :message,
                    :payloadJson,
                    :recipientIdsJson,
                    :eventCreatedAt,
                    null,
                    :now,
                    :now
                )
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("recipientId", recipientId)
                    .addValue("eventId", event.id)
                    .addValue("topic", event.topic)
                    .addValue("type", event.type)
                    .addValue("severity", event.severity.name)
                    .addValue("title", event.title)
                    .addValue("message", event.message)
                    .addValue("payloadJson", jsonCodec.canonicalString(jsonCodec.toDocument(event.payload)))
                    .addValue("recipientIdsJson", jsonCodec.canonicalString(jsonCodec.toDocument(event.recipientIds)))
                    .addValue("eventCreatedAt", event.createdAt.toTimestamp())
                    .addValue("now", now.toTimestamp()),
            )
        }.onFailure { error ->
            if (error !is DuplicateKeyException) throw error
        }
    }

    private fun findOne(
        recipientId: String,
        eventId: String,
    ): NotificationInboxRecord? =
        jdbc.query(
            """
            select *
            from skeleton_notification_inbox
            where recipient_id = :recipientId
              and event_id = :eventId
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("recipientId", recipientId)
                .addValue("eventId", eventId),
        ) { rs, _ -> rs.toRecord() }
            .firstOrNull()

    private fun whereClause(query: NotificationInboxQuery): String =
        buildString {
            append("where recipient_id = :recipientId")
            if (query.unreadOnly) {
                append(" and read_at is null")
            }
            if (!query.topic.isNullOrBlank()) {
                append(" and topic = :topic")
            }
        }

    private fun ResultSet.toRecord(): NotificationInboxRecord {
        val event = NotificationEvent(
            id = getString("event_id"),
            topic = getString("topic"),
            type = getString("type"),
            recipientIds = readRecipientIds(getString("recipient_ids_json")),
            severity = NotificationSeverity.valueOf(getString("severity")),
            title = getString("title"),
            message = getString("message"),
            payload = readPayload(getString("payload_json")),
            createdAt = getTimestamp("event_created_at").toInstant(),
        )
        return NotificationInboxRecord(
            recipientId = getString("recipient_id"),
            event = event,
            readAt = getTimestamp("read_at")?.toInstant(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readPayload(raw: String): Map<String, Any?> =
        jsonCodec.fromDocument(jsonCodec.parse(raw), Map::class) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun readRecipientIds(raw: String): Set<String> =
        (jsonCodec.fromDocument(jsonCodec.parse(raw), Set::class) as Set<Any?>)
            .mapNotNullTo(linkedSetOf()) { value ->
                value?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(this)
}
