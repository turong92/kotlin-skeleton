package dev.sumin.skeleton.notification

data class NotificationPublishResult(
    val eventId: String,
    val deliveredSubscribers: Int,
)

fun interface NotificationPublisher {
    fun publish(event: NotificationEvent): NotificationPublishResult
}

fun interface NotificationSubscriber {
    fun onNotification(event: NotificationEvent)
}

interface NotificationSubscription : AutoCloseable {
    val id: String
    val topics: Set<String>
    override fun close()
}

interface NotificationSubscriptionRegistry {
    fun subscribe(
        topics: Set<String> = emptySet(),
        subscriber: NotificationSubscriber,
    ): NotificationSubscription
}

interface NotificationBroker : NotificationPublisher, NotificationSubscriptionRegistry
