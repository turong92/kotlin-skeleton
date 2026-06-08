package dev.sumin.skeleton.notification.sse

import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationSseServiceTest {
    @Test
    fun `connect subscribes with normalized topics and closes subscription on completion`() {
        val registry = RecordingSubscriptionRegistry()
        val service = NotificationSseService(
            subscriptionRegistry = registry,
            properties = NotificationSseProperties(),
        )

        val emitter = service.connect(listOf(" orders ", "", "runs"))

        assertEquals(setOf("orders", "runs"), registry.subscription.topics)

        emitter.complete()

        assertTrue(registry.subscription.closed)
    }

    @Test
    fun `connect allows all topics when topic list is empty`() {
        val registry = RecordingSubscriptionRegistry()
        val service = NotificationSseService(
            subscriptionRegistry = registry,
            properties = NotificationSseProperties(),
        )

        service.connect(emptyList())

        assertEquals(emptySet(), registry.subscription.topics)
    }

    private class RecordingSubscriptionRegistry : NotificationSubscriptionRegistry {
        lateinit var subscriber: NotificationSubscriber
        lateinit var subscription: RecordingSubscription

        override fun subscribe(
            topics: Set<String>,
            subscriber: NotificationSubscriber,
        ): NotificationSubscription {
            this.subscriber = subscriber
            subscription = RecordingSubscription(topics)
            return subscription
        }
    }

    private class RecordingSubscription(
        override val topics: Set<String>,
    ) : NotificationSubscription {
        override val id: String = "sub-1"
        var closed: Boolean = false

        override fun close() {
            closed = true
        }
    }
}
