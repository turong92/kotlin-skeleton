package dev.sumin.skeleton.api

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublishResult
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentFailureEvent
import dev.sumin.skeleton.payment.PaymentOperation
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonPaymentFailureNotificationConfigurationTest {
    @Test
    fun `payment failure handler publishes standard notification event`() {
        val events = mutableListOf<NotificationEvent>()
        val handler = SkeletonPaymentFailureNotificationConfiguration().paymentFailureNotificationHandler(
            notificationPublisher = NotificationPublisher { event ->
                events += event
                NotificationPublishResult(eventId = event.id, deliveredSubscribers = 1)
            },
        )
        val exception = PaymentProviderException(
            providerError = PaymentProviderError(
                provider = "toss",
                code = "PROVIDER_FAILED",
                message = "provider failed",
                trace = ProviderTrace(
                    provider = "toss",
                    providerRequestId = "trace-provider-1",
                    rawStatus = "500",
                ),
                upstreamStatus = 500,
                retryable = true,
            ),
            clientName = "payment-toss",
            method = "POST",
            uri = URI.create("https://provider.example.test/payments"),
        )

        handler.handle(
            PaymentFailureEvent(
                operation = PaymentOperation.REFUND,
                provider = "toss",
                providerPaymentId = "pay_1",
                amount = PaymentAmount(amount = 1_000, currency = "KRW"),
                country = "KR",
                reason = "duplicate",
                idempotencyKey = IdempotencyKey("refund-idem"),
                exception = exception,
            ),
        )

        val event = events.single()
        assertEquals("payment", event.topic)
        assertEquals("payment.refund.failed", event.type)
        assertEquals(NotificationSeverity.ERROR, event.severity)
        assertEquals("Payment refund failed", event.title)
        assertEquals("toss payment refund failed for pay_1", event.message)
        assertEquals("toss", event.payload["provider"])
        assertEquals("REFUND", event.payload["operation"])
        assertEquals("pay_1", event.payload["providerPaymentId"])
        assertEquals(1_000L, event.payload["amount"])
        assertEquals("KRW", event.payload["currency"])
        assertEquals("refund-idem", event.payload["idempotencyKey"])
        assertEquals(500, event.payload["upstreamStatus"])
        assertEquals(true, event.payload["retryable"])
        assertEquals("PROVIDER_FAILED", event.payload["providerCode"])
        assertEquals("trace-provider-1", event.payload["providerRequestId"])
    }
}
