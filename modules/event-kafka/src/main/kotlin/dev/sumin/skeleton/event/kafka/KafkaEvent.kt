package dev.sumin.skeleton.event.kafka

import java.time.Instant
import java.util.UUID
import org.springframework.context.ApplicationEvent

class KafkaEvent(
    val topic: String,
    val type: String,
    val id: String = UUID.randomUUID().toString(),
    val payload: Any? = null,
    val partitionKey: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    source: Any = payload ?: type,
) : ApplicationEvent(source) {
    init {
        require(topic.isNotBlank()) { "topic must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
    }
}
