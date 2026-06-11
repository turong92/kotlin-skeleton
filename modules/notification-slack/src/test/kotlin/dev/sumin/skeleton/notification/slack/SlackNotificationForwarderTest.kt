package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.slf4j.MDC

class SlackNotificationForwarderTest {
    @AfterTest
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `does not subscribe when notification event bridge is disabled`() {
        val registry = RecordingSubscriptionRegistry()

        SlackNotificationForwarder(
            sender = RecordingSlackAlertSender(),
            properties = SlackNotificationProperties(notificationEvents = false),
            subscriptionRegistry = registry,
        )

        assertNull(registry.subscriber)
    }

    @Test
    fun `forwards notification event as Slack alert`() {
        val registry = RecordingSubscriptionRegistry()
        val sender = RecordingSlackAlertSender()
        MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")

        SlackNotificationForwarder(
            sender = sender,
            properties = SlackNotificationProperties(notificationEvents = true),
            subscriptionRegistry = registry,
        )

        registry.subscriber?.onNotification(
            NotificationEvent(
                topic = "payment",
                type = "payment.failed",
                severity = NotificationSeverity.ERROR,
                title = "Payment failed",
                message = "approve failed",
                payload = mapOf("orderId" to "order-1", "nullable" to null),
            ),
        )

        val alert = sender.alerts.single()
        assertEquals("Payment failed", alert.title)
        assertEquals("approve failed", alert.message)
        assertEquals(SlackAlertSeverity.ERROR, alert.severity)
        assertEquals("payment", alert.route)
        assertEquals("payment", alert.topic)
        assertEquals("order-1", alert.fields["orderId"])
        assertEquals("payment.failed", alert.fields["type"])
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", alert.trace.traceId)
        assertEquals("00f067aa0ba902b7", alert.trace.spanId)
    }

    private class RecordingSubscriptionRegistry : NotificationSubscriptionRegistry {
        var subscriber: NotificationSubscriber? = null

        override fun subscribe(
            topics: Set<String>,
            subscriber: NotificationSubscriber,
        ): NotificationSubscription {
            this.subscriber = subscriber
            return object : NotificationSubscription {
                override val id: String = "recording"
                override val topics: Set<String> = topics
                override fun close() = Unit
            }
        }
    }

    private class RecordingSlackAlertSender : SlackAlertSender {
        val alerts = mutableListOf<SlackAlert>()

        override fun send(alert: SlackAlert) {
            alerts += alert
        }
    }
}
