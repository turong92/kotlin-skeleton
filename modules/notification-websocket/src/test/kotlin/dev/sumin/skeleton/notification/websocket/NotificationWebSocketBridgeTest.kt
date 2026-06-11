package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate

class NotificationWebSocketBridgeTest {
    @Test
    fun `bridge subscribes to broker and forwards events to topic destination`() {
        val registry = RecordingSubscriptionRegistry()
        val channel = RecordingMessageChannel()
        val bridge = NotificationWebSocketBridge(
            subscriptionRegistry = registry,
            messagingTemplate = SimpMessagingTemplate(channel),
            destinationResolver = DefaultNotificationWebSocketDestinationResolver(NotificationWebSocketProperties()),
            properties = NotificationWebSocketProperties(),
        )
        val event = NotificationEvent(topic = "orders.created", type = "created")

        bridge.start()
        registry.subscriber.onNotification(event)

        val sent = channel.messages.single()
        assertEquals("/topic/notifications/orders.created", sent.headers[SimpMessageHeaderAccessor.DESTINATION_HEADER])
        assertSame(event, sent.payload)
    }

    @Test
    fun `bridge closes notification subscription when stopped`() {
        val registry = RecordingSubscriptionRegistry()
        val bridge = NotificationWebSocketBridge(
            subscriptionRegistry = registry,
            messagingTemplate = SimpMessagingTemplate(RecordingMessageChannel()),
            destinationResolver = DefaultNotificationWebSocketDestinationResolver(NotificationWebSocketProperties()),
            properties = NotificationWebSocketProperties(),
        )

        bridge.start()
        assertFalse(registry.subscription.closed)

        bridge.stop()

        assertTrue(registry.subscription.closed)
    }

    @Test
    fun `default destination resolver includes user destination when event contains user id`() {
        val resolver = DefaultNotificationWebSocketDestinationResolver(NotificationWebSocketProperties())
        val event = NotificationEvent(
            topic = "orders",
            type = "created",
            payload = mapOf("userId" to "user-1"),
        )

        val destinations = resolver.resolveDestinations(event)

        assertEquals(
            listOf(
                NotificationWebSocketDestination.topic("/topic/notifications/orders"),
                NotificationWebSocketDestination.user("user-1", "/queue/notifications"),
            ),
            destinations,
        )
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

    private class RecordingMessageChannel : MessageChannel {
        val messages = mutableListOf<Message<*>>()

        override fun send(message: Message<*>): Boolean {
            messages += message
            return true
        }

        override fun send(
            message: Message<*>,
            timeout: Long,
        ): Boolean {
            messages += message
            return true
        }
    }
}
