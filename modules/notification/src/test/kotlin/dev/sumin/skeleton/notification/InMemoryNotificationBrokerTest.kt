package dev.sumin.skeleton.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InMemoryNotificationBrokerTest {
    @Test
    fun `publish delivers matching topic notifications and reports delivery count`() {
        val broker = InMemoryNotificationBroker()
        val orderEvents = mutableListOf<NotificationEvent>()
        val allEvents = mutableListOf<NotificationEvent>()
        broker.subscribe(topics = setOf("orders")) { event -> orderEvents += event }
        broker.subscribe(topics = emptySet()) { event -> allEvents += event }

        val result = broker.publish(
            NotificationEvent(
                topic = "orders",
                type = "created",
                title = "Order created",
                message = "Order A was created",
                payload = mapOf("orderId" to "A"),
            ),
        )

        assertEquals(2, result.deliveredSubscribers)
        assertEquals(result.eventId, orderEvents.single().id)
        assertEquals("orders", orderEvents.single().topic)
        assertEquals("A", orderEvents.single().payload["orderId"])
        assertEquals(1, allEvents.size)
    }

    @Test
    fun `publish skips non matching topic subscribers`() {
        val broker = InMemoryNotificationBroker()
        val received = mutableListOf<NotificationEvent>()
        broker.subscribe(topics = setOf("orders")) { event -> received += event }

        val result = broker.publish(NotificationEvent(topic = "runs", type = "progress"))

        assertEquals(0, result.deliveredSubscribers)
        assertEquals(emptyList(), received)
    }

    @Test
    fun `closed subscription no longer receives events`() {
        val broker = InMemoryNotificationBroker()
        val received = mutableListOf<NotificationEvent>()
        val subscription = broker.subscribe(topics = setOf("orders")) { event -> received += event }

        subscription.close()
        val result = broker.publish(NotificationEvent(topic = "orders", type = "created"))

        assertEquals(0, result.deliveredSubscribers)
        assertEquals(emptyList(), received)
    }

    @Test
    fun `failing subscriber is closed and does not prevent other deliveries`() {
        val broker = InMemoryNotificationBroker()
        var attempts = 0
        val received = mutableListOf<NotificationEvent>()
        broker.subscribe(topics = setOf("orders")) {
            attempts += 1
            throw IllegalStateException("subscriber disconnected")
        }
        broker.subscribe(topics = setOf("orders")) { event -> received += event }

        val first = broker.publish(NotificationEvent(topic = "orders", type = "created"))
        val second = broker.publish(NotificationEvent(topic = "orders", type = "updated"))

        assertEquals(1, first.deliveredSubscribers)
        assertEquals(1, second.deliveredSubscribers)
        assertEquals(1, attempts)
        assertEquals(listOf("created", "updated"), received.map { it.type })
    }

    @Test
    fun `event requires non blank topic and type`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationEvent(topic = " ", type = "created")
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationEvent(topic = "orders", type = " ")
        }
    }
}
