package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.observability.ObservabilityLink
import dev.sumin.skeleton.common.observability.ObservabilityLinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class SlackAlertMessageFactoryTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `builds Slack payload with trace and non-null fields`() {
        val factory = SlackAlertMessageFactory(SlackNotificationProperties())
        val payload = factory.create(
            alert = SlackAlert(
                title = "Payment failed",
                message = "Toss approve failed",
                severity = SlackAlertSeverity.ERROR,
                topic = "billing",
                fields = mapOf(
                    "orderId" to "order-1",
                    "accessToken" to "token-1",
                    "empty" to null,
                ),
                trace = SlackTraceContext(
                    traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                    spanId = "00f067aa0ba902b7",
                ),
            ),
            route = SlackNotificationProperties.Route(
                username = "Skeleton",
                iconEmoji = ":warning:",
            ),
        )

        assertEquals("Skeleton", payload.username)
        assertEquals(":warning:", payload.iconEmoji)
        assertEquals("[ERROR] Payment failed", payload.text)

        val body = mapper.writeValueAsString(payload)
        assertTrue(body.contains("icon_emoji"))
        assertTrue(body.contains("Payment failed"))
        assertTrue(body.contains("Toss approve failed"))
        assertTrue(body.contains("traceId"))
        assertTrue(body.contains("4bf92f3577b34da6a3ce929d0e0e4736"))
        assertTrue(body.contains("spanId"))
        assertTrue(body.contains("orderId"))
        assertTrue(body.contains("[REDACTED]"))
        assertFalse(body.contains("token-1"))
        assertFalse(body.contains("empty"))
    }

    @Test
    fun `renders observability links when alert has links`() {
        val factory = SlackAlertMessageFactory(SlackNotificationProperties())
        val payload = factory.create(
            alert = SlackAlert(
                title = "Async failed",
                message = "worker failed",
                severity = SlackAlertSeverity.ERROR,
                topic = "async.exception",
                links = listOf(
                    ObservabilityLink(
                        id = "logs",
                        label = "Logs by traceId",
                        url = "https://grafana.example/explore?traceId=4bf92f3577b34da6a3ce929d0e0e4736",
                        kind = ObservabilityLinkKind.LOGS,
                    ),
                ),
            ),
        )

        val body = mapper.writeValueAsString(payload)

        assertTrue(body.contains("Links:"))
        assertTrue(body.contains("<https://grafana.example/explore?traceId=4bf92f3577b34da6a3ce929d0e0e4736|Logs by traceId>"))
        assertEquals(
            "Links: <https://grafana.example/explore?traceId=4bf92f3577b34da6a3ce929d0e0e4736|Logs by traceId>",
            payload.blocks[2].elements.single().text,
        )
        assertTrue(payload.blocks[3].elements.single().text.startsWith("occurredAt="))
    }
}
