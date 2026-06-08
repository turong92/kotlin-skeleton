package dev.sumin.skeleton.notification

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class InMemoryNotificationBroker : NotificationBroker {
    private val subscriptions = ConcurrentHashMap<String, BrokerSubscription>()

    override fun publish(event: NotificationEvent): NotificationPublishResult {
        var delivered = 0
        subscriptions.values.forEach { subscription ->
            if (subscription.matches(event.topic)) {
                subscription.subscriber.onNotification(event)
                delivered += 1
            }
        }
        return NotificationPublishResult(eventId = event.id, deliveredSubscribers = delivered)
    }

    override fun subscribe(
        topics: Set<String>,
        subscriber: NotificationSubscriber,
    ): NotificationSubscription {
        val normalizedTopics = topics.mapNotNullTo(linkedSetOf()) { topic ->
            topic.trim().takeIf { it.isNotBlank() }
        }
        val subscription = BrokerSubscription(
            id = UUID.randomUUID().toString(),
            topics = normalizedTopics,
            subscriber = subscriber,
            onClose = { id -> subscriptions.remove(id) },
        )
        subscriptions[subscription.id] = subscription
        return subscription
    }

    private class BrokerSubscription(
        override val id: String,
        override val topics: Set<String>,
        val subscriber: NotificationSubscriber,
        private val onClose: (String) -> Unit,
    ) : NotificationSubscription {
        private val closed = AtomicBoolean(false)

        fun matches(topic: String): Boolean =
            !closed.get() && (topics.isEmpty() || topic in topics)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                onClose(id)
            }
        }
    }
}
