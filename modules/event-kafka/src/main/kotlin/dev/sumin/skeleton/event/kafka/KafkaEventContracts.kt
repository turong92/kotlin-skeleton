package dev.sumin.skeleton.event.kafka

import org.springframework.context.ApplicationEventPublisher

data class KafkaEventPublishResult(
    val eventId: String,
    val topic: String,
)

fun interface KafkaEventPublisher {
    fun publish(event: KafkaEvent): KafkaEventPublishResult
}

internal class ApplicationKafkaEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) : KafkaEventPublisher {
    override fun publish(event: KafkaEvent): KafkaEventPublishResult {
        applicationEventPublisher.publishEvent(event)
        return KafkaEventPublishResult(eventId = event.id, topic = event.topic)
    }
}

data class KafkaEventMessage(
    val topic: String,
    val key: String?,
    val payload: Any?,
    val headers: Map<String, String>,
    val event: KafkaEvent,
)

fun interface KafkaEventSender {
    fun send(message: KafkaEventMessage)
}

fun interface KafkaEventPartitionKeyStrategy {
    fun partitionKey(event: KafkaEvent): String?
}
