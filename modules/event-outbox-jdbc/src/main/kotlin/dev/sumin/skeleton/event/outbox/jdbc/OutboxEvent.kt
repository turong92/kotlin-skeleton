package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.KafkaEvent
import java.time.Instant

enum class OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}

data class OutboxEventRecord(
    val id: String,
    val eventId: String,
    val event: KafkaEvent,
    val status: OutboxEventStatus,
    val attempts: Int,
    val availableAt: Instant,
    val publishedAt: Instant?,
    val failedAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface OutboxEventRepository {
    fun append(event: KafkaEvent): OutboxEventRecord

    fun findDue(
        now: Instant,
        limit: Int,
    ): List<OutboxEventRecord>

    fun markPublished(
        id: String,
        publishedAt: Instant,
    )

    fun markFailed(
        id: String,
        failedAt: Instant,
        nextAvailableAt: Instant,
        lastError: String,
    )
}
