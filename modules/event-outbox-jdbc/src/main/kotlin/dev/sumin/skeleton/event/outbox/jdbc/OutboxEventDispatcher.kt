package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.KafkaEventMessageFactory
import dev.sumin.skeleton.event.kafka.KafkaEventSender
import java.time.Instant

data class OutboxDispatchResult(
    val scanned: Int,
    val published: Int,
    val failed: Int,
)

class OutboxEventDispatcher(
    private val repository: OutboxEventRepository,
    private val sender: KafkaEventSender,
    private val messageFactory: KafkaEventMessageFactory,
    private val properties: EventOutboxJdbcProperties,
) {
    fun dispatchDue(now: Instant = Instant.now()): OutboxDispatchResult {
        val dueEvents = repository.findDue(now, properties.batchSize)
        var published = 0
        var failed = 0

        dueEvents.forEach { record ->
            runCatching {
                sender.send(messageFactory.toMessage(record.event))
            }.onSuccess {
                repository.markPublished(record.id, now)
                published += 1
            }.onFailure { throwable ->
                repository.markFailed(
                    id = record.id,
                    failedAt = now,
                    nextAvailableAt = now.plus(properties.retryBackoff),
                    lastError = throwable.message ?: throwable::class.java.name,
                )
                failed += 1
            }
        }

        return OutboxDispatchResult(
            scanned = dueEvents.size,
            published = published,
            failed = failed,
        )
    }
}
