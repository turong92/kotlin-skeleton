package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.ConfiguredKafkaEventPartitionKeyStrategy
import dev.sumin.skeleton.event.kafka.EventKafkaProperties
import dev.sumin.skeleton.event.kafka.KafkaEvent
import dev.sumin.skeleton.event.kafka.KafkaEventMessage
import dev.sumin.skeleton.event.kafka.KafkaEventMessageFactory
import dev.sumin.skeleton.event.kafka.KafkaEventSender
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxEventDispatcherTest {
    private val messageFactory = KafkaEventMessageFactory(
        properties = EventKafkaProperties(environment = "qa"),
        partitionKeyStrategy = ConfiguredKafkaEventPartitionKeyStrategy(EventKafkaProperties(environment = "qa")),
    )

    @Test
    fun `dispatcher sends due events and marks them published`() {
        val repository = RecordingOutboxEventRepository(
            due = listOf(recordFor(KafkaEvent(topic = "payments", type = "payment.created", id = "event-1"))),
        )
        val sender = RecordingKafkaEventSender()
        val dispatcher = OutboxEventDispatcher(
            repository = repository,
            sender = sender,
            messageFactory = messageFactory,
            properties = EventOutboxJdbcProperties(batchSize = 10),
        )

        val result = dispatcher.dispatchDue(Instant.parse("2026-06-22T00:00:00Z"))

        assertThat(sender.sent).hasSize(1)
        assertThat(sender.sent.single().headers["skeleton-event-id"]).isEqualTo("event-1")
        assertThat(repository.publishedIds).containsExactly("outbox-1")
        assertThat(result.published).isEqualTo(1)
        assertThat(result.failed).isEqualTo(0)
    }

    @Test
    fun `dispatcher marks failed events retryable after backoff`() {
        val repository = RecordingOutboxEventRepository(
            due = listOf(recordFor(KafkaEvent(topic = "payments", type = "payment.failed", id = "event-2"))),
        )
        val dispatcher = OutboxEventDispatcher(
            repository = repository,
            sender = FailingKafkaEventSender(),
            messageFactory = messageFactory,
            properties = EventOutboxJdbcProperties(batchSize = 10, retryBackoff = Duration.ofSeconds(30)),
        )
        val now = Instant.parse("2026-06-22T00:00:00Z")

        val result = dispatcher.dispatchDue(now)

        assertThat(repository.failures).hasSize(1)
        assertThat(repository.failures.single().id).isEqualTo("outbox-1")
        assertThat(repository.failures.single().nextAvailableAt).isEqualTo(now.plusSeconds(30))
        assertThat(repository.failures.single().lastError).contains("kafka down")
        assertThat(result.published).isEqualTo(0)
        assertThat(result.failed).isEqualTo(1)
    }

    private fun recordFor(event: KafkaEvent): OutboxEventRecord =
        OutboxEventRecord(
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

    private class RecordingOutboxEventRepository(
        private val due: List<OutboxEventRecord>,
    ) : OutboxEventRepository {
        val publishedIds = mutableListOf<String>()
        val failures = mutableListOf<Failure>()

        override fun append(event: KafkaEvent): OutboxEventRecord = error("not used")

        override fun findDue(now: Instant, limit: Int): List<OutboxEventRecord> = due.take(limit)

        override fun markPublished(id: String, publishedAt: Instant) {
            publishedIds += id
        }

        override fun markFailed(
            id: String,
            failedAt: Instant,
            nextAvailableAt: Instant,
            lastError: String,
        ) {
            failures += Failure(id, failedAt, nextAvailableAt, lastError)
        }
    }

    private class RecordingKafkaEventSender : KafkaEventSender {
        val sent = mutableListOf<KafkaEventMessage>()

        override fun send(message: KafkaEventMessage) {
            sent += message
        }
    }

    private class FailingKafkaEventSender : KafkaEventSender {
        override fun send(message: KafkaEventMessage) {
            error("kafka down")
        }
    }

    private data class Failure(
        val id: String,
        val failedAt: Instant,
        val nextAvailableAt: Instant,
        val lastError: String,
    )
}
