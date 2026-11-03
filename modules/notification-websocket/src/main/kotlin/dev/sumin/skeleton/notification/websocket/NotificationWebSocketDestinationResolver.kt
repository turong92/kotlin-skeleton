package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationEvent

fun interface NotificationWebSocketDestinationResolver {
    fun resolveDestinations(event: NotificationEvent): List<NotificationWebSocketDestination>
}

data class NotificationWebSocketDestination(
    val destination: String,
    val user: String? = null,
) {
    companion object {
        fun topic(destination: String): NotificationWebSocketDestination =
            NotificationWebSocketDestination(destination = destination)

        fun user(
            user: String,
            destination: String,
        ): NotificationWebSocketDestination =
            NotificationWebSocketDestination(destination = destination, user = user)
    }
}

class DefaultNotificationWebSocketDestinationResolver(
    private val properties: NotificationWebSocketProperties,
) : NotificationWebSocketDestinationResolver {
    override fun resolveDestinations(event: NotificationEvent): List<NotificationWebSocketDestination> {
        val destinations = mutableListOf<NotificationWebSocketDestination>()
        // 받는 사람이 정해진 알림은 공개 토픽으로 보내지 않는다(구독자 전원에게 새 나간다) - 받는 사람마다 사용자 큐로만
        if (event.recipientIds.isNotEmpty()) {
            val queue = normalizedDestination(properties.broker.userNotificationDestination)
            return event.recipientIds.map { NotificationWebSocketDestination.user(it, queue) }
        }
        destinations += NotificationWebSocketDestination.topic(
            joinDestination(properties.broker.notificationDestinationPrefix, event.topic),
        )

        val userId = event.payload[properties.broker.userIdPayloadKey]
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (userId != null) {
            destinations += NotificationWebSocketDestination.user(
                user = userId,
                destination = normalizedDestination(properties.broker.userNotificationDestination),
            )
        }

        return destinations.distinct()
    }

    private fun joinDestination(
        prefix: String,
        suffix: String,
    ): String {
        val normalizedPrefix = normalizedDestination(prefix)
        val normalizedSuffix = suffix.trim().trim('/')
        return if (normalizedSuffix.isBlank()) {
            normalizedPrefix
        } else {
            "$normalizedPrefix/$normalizedSuffix"
        }
    }

    private fun normalizedDestination(destination: String): String {
        val normalized = destination.trim().trimEnd('/')
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }
}
