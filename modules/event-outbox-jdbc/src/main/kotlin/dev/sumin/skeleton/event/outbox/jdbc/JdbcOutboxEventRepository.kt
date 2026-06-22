package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.KafkaEvent
import dev.sumin.skeleton.json.JsonCodec
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class JdbcOutboxEventRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val jsonCodec: JsonCodec,
) : OutboxEventRepository {
    override fun append(event: KafkaEvent): OutboxEventRecord {
        val now = Instant.now()
        val id = UUID.randomUUID().toString()
        runCatching {
            jdbc.update(
                """
                insert into skeleton_event_outbox (
                    id,
                    event_id,
                    topic,
                    type,
                    partition_key,
                    payload_json,
                    headers_json,
                    status,
                    attempts,
                    available_at,
                    event_created_at,
                    published_at,
                    failed_at,
                    last_error,
                    created_at,
                    updated_at
                ) values (
                    :id,
                    :eventId,
                    :topic,
                    :type,
                    :partitionKey,
                    :payloadJson,
                    :headersJson,
                    :status,
                    0,
                    :availableAt,
                    :eventCreatedAt,
                    null,
                    null,
                    null,
                    :now,
                    :now
                )
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("eventId", event.id)
                    .addValue("topic", event.topic)
                    .addValue("type", event.type)
                    .addValue("partitionKey", event.partitionKey)
                    .addValue("payloadJson", event.payload.toJson())
                    .addValue("headersJson", jsonCodec.canonicalString(jsonCodec.toDocument(event.headers)))
                    .addValue("status", OutboxEventStatus.PENDING.name)
                    .addValue("availableAt", now.toTimestamp())
                    .addValue("eventCreatedAt", event.createdAt.toTimestamp())
                    .addValue("now", now.toTimestamp()),
            )
        }.onFailure { error ->
            if (error !is DuplicateKeyException) throw error
        }
        return findByEventId(event.id)
            ?: error("Outbox event was not found after append eventId=${event.id}")
    }

    override fun findDue(
        now: Instant,
        limit: Int,
    ): List<OutboxEventRecord> =
        jdbc.query(
            """
            select *
            from skeleton_event_outbox
            where status in (:statuses)
              and available_at <= :now
            order by available_at asc, created_at asc, id asc
            limit :limit
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("statuses", listOf(OutboxEventStatus.PENDING.name, OutboxEventStatus.FAILED.name))
                .addValue("now", now.toTimestamp())
                .addValue("limit", limit.coerceAtLeast(1)),
        ) { rs, _ -> rs.toRecord() }

    override fun markPublished(
        id: String,
        publishedAt: Instant,
    ) {
        jdbc.update(
            """
            update skeleton_event_outbox
            set status = :status,
                published_at = :publishedAt,
                updated_at = :publishedAt
            where id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", OutboxEventStatus.PUBLISHED.name)
                .addValue("publishedAt", publishedAt.toTimestamp()),
        )
    }

    override fun markFailed(
        id: String,
        failedAt: Instant,
        nextAvailableAt: Instant,
        lastError: String,
    ) {
        jdbc.update(
            """
            update skeleton_event_outbox
            set status = :status,
                attempts = attempts + 1,
                available_at = :nextAvailableAt,
                failed_at = :failedAt,
                last_error = :lastError,
                updated_at = :failedAt
            where id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", OutboxEventStatus.FAILED.name)
                .addValue("nextAvailableAt", nextAvailableAt.toTimestamp())
                .addValue("failedAt", failedAt.toTimestamp())
                .addValue("lastError", lastError.take(2000)),
        )
    }

    private fun findByEventId(eventId: String): OutboxEventRecord? =
        jdbc.query(
            """
            select *
            from skeleton_event_outbox
            where event_id = :eventId
            """.trimIndent(),
            MapSqlParameterSource().addValue("eventId", eventId),
        ) { rs, _ -> rs.toRecord() }
            .firstOrNull()

    private fun ResultSet.toRecord(): OutboxEventRecord {
        val event = KafkaEvent(
            id = getString("event_id"),
            topic = getString("topic"),
            type = getString("type"),
            payload = readPayload(getString("payload_json")),
            partitionKey = getString("partition_key"),
            headers = readHeaders(getString("headers_json")),
            createdAt = getTimestamp("event_created_at").toInstant(),
        )
        return OutboxEventRecord(
            id = getString("id"),
            eventId = getString("event_id"),
            event = event,
            status = OutboxEventStatus.valueOf(getString("status")),
            attempts = getInt("attempts"),
            availableAt = getTimestamp("available_at").toInstant(),
            publishedAt = getTimestamp("published_at")?.toInstant(),
            failedAt = getTimestamp("failed_at")?.toInstant(),
            lastError = getString("last_error"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
    }

    private fun Any?.toJson(): String =
        if (this == null) {
            "null"
        } else {
            jsonCodec.canonicalString(jsonCodec.toDocument(this))
        }

    private fun readPayload(raw: String): Any? {
        return if (raw.trim() == "null") {
            null
        } else {
            jsonCodec.fromDocument(jsonCodec.parse(raw), Any::class)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readHeaders(raw: String): Map<String, String> =
        (jsonCodec.fromDocument(jsonCodec.parse(raw), Map::class) as Map<Any?, Any?>)
            .mapNotNull { (key, value) ->
                val normalizedKey = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val normalizedValue = value?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                normalizedKey to normalizedValue
            }
            .toMap()

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(this)
}
