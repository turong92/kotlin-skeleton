package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.KafkaEvent
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxKafkaEventPublisherTest {
    @Test
    fun `publisher stores event in outbox without sending immediately`() {
        val repository = RecordingOutboxEventRepository()
        val publisher = OutboxKafkaEventPublisher(repository)

        val result = publisher.publish(
            KafkaEvent(
                topic = "payments",
                type = "payment.created",
                id = "event-1",
            ),
        )

        assertThat(result.eventId).isEqualTo("event-1")
        assertThat(result.topic).isEqualTo("payments")
        assertThat(repository.appended.single().id).isEqualTo("event-1")
    }

    private class RecordingOutboxEventRepository : OutboxEventRepository {
        val appended = mutableListOf<KafkaEvent>()

        override fun append(event: KafkaEvent): OutboxEventRecord {
            appended += event
            return OutboxEventRecord(
                id = "outbox-1",
                eventId = event.id,
                event = event,
                status = OutboxEventStatus.PENDING,
                attempts = 0,
                availableAt = Instant.parse("2026-06-22T00:00:00Z"),
                publishedAt = null,
                failedAt = null,
                lastError = null,
                createdAt = Instant.parse("2026-06-22T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-22T00:00:00Z"),
            )
        }

        override fun findDue(now: Instant, limit: Int): List<OutboxEventRecord> = emptyList()

        override fun markPublished(id: String, publishedAt: Instant) = Unit

        override fun markFailed(id: String, failedAt: Instant, nextAvailableAt: Instant, lastError: String) = Unit
    }
}
