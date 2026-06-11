package dev.sumin.skeleton.notification.slack

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackNotificationPropertiesTest {
    @Test
    fun `defaults keep Slack disabled and filter below warning`() {
        val properties = SlackNotificationProperties()

        assertFalse(properties.enabled)
        assertEquals("slack", properties.clientName)
        assertEquals("operations", properties.defaultTopic)
        assertEquals(SlackAlertSeverity.WARNING, properties.minimumSeverity)
        assertEquals(Duration.ofSeconds(2), properties.timeout)
        assertEquals(1, properties.retryAttempts)
        assertTrue(properties.shouldSend(SlackAlertSeverity.WARNING))
        assertTrue(properties.shouldSend(SlackAlertSeverity.ERROR))
        assertFalse(properties.shouldSend(SlackAlertSeverity.INFO))
        assertFalse(properties.shouldSend(SlackAlertSeverity.SUCCESS))
    }

    @Test
    fun `route can override webhook topic and minimum severity`() {
        val properties = SlackNotificationProperties(
            webhookUrl = "https://hooks.slack.example/default",
            routes = mapOf(
                "payment" to SlackNotificationProperties.Route(
                    webhookUrl = "https://hooks.slack.example/payment",
                    topic = "billing",
                    minimumSeverity = SlackAlertSeverity.ERROR,
                ),
            ),
        )

        val paymentRoute = properties.route("payment")
        val fallbackRoute = properties.route("missing")

        assertEquals("https://hooks.slack.example/payment", paymentRoute.webhookUrl)
        assertEquals("billing", paymentRoute.topic)
        assertEquals(SlackAlertSeverity.ERROR, paymentRoute.minimumSeverity)
        assertFalse(properties.shouldSend(SlackAlertSeverity.WARNING, paymentRoute))
        assertTrue(properties.shouldSend(SlackAlertSeverity.ERROR, paymentRoute))

        assertEquals("https://hooks.slack.example/default", fallbackRoute.webhookUrl)
        assertEquals("operations", fallbackRoute.topic)
        assertNull(fallbackRoute.minimumSeverity)
    }
}
