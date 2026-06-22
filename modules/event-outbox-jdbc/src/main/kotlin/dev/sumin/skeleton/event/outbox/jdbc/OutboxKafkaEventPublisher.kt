package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.KafkaEvent
import dev.sumin.skeleton.event.kafka.KafkaEventPublishResult
import dev.sumin.skeleton.event.kafka.KafkaEventPublisher

class OutboxKafkaEventPublisher(
    private val repository: OutboxEventRepository,
) : KafkaEventPublisher {
    override fun publish(event: KafkaEvent): KafkaEventPublishResult {
        val saved = repository.append(event)
        return KafkaEventPublishResult(eventId = saved.eventId, topic = saved.event.topic)
    }
}
