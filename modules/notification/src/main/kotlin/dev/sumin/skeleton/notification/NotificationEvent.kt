package dev.sumin.skeleton.notification

import java.time.Instant
import java.util.UUID

data class NotificationEvent(
    val topic: String,
    val type: String,
    val id: String = UUID.randomUUID().toString(),
    val recipientIds: Set<String> = emptySet(),
    val severity: NotificationSeverity = NotificationSeverity.INFO,
    val title: String? = null,
    val message: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(topic.isNotBlank()) { "topic must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
        require(recipientIds.none { it.isBlank() }) { "recipientIds must not contain blank values" }
    }
}

enum class NotificationSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}
