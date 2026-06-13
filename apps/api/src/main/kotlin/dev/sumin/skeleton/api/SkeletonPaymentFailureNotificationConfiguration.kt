package dev.sumin.skeleton.api

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.payment.PaymentFailureEvent
import dev.sumin.skeleton.payment.PaymentFailureHandler
import dev.sumin.skeleton.payment.PaymentProviderException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SkeletonPaymentFailureNotificationConfiguration {
    @Bean
    fun paymentFailureNotificationHandler(
        notificationPublisher: NotificationPublisher,
    ): PaymentFailureHandler =
        PaymentFailureHandler { event ->
            notificationPublisher.publish(event.toNotificationEvent())
        }

    private fun PaymentFailureEvent.toNotificationEvent(): NotificationEvent {
        val operation = operation.name.lowercase()
        return NotificationEvent(
            topic = "payment",
            type = "payment.$operation.failed",
            severity = NotificationSeverity.ERROR,
            title = "Payment $operation failed",
            message = "$provider payment $operation failed for $providerPaymentId",
            payload = payload(),
        )
    }

    private fun PaymentFailureEvent.payload(): Map<String, Any?> {
        val providerException = exception as? PaymentProviderException
        return buildMap {
            put("operation", operation.name)
            put("provider", provider)
            put("providerPaymentId", providerPaymentId)
            put("amount", amount?.amount)
            put("currency", amount?.normalizedCurrency)
            put("country", country)
            put("merchantReferenceId", merchantReferenceId)
            put("reason", reason)
            put("idempotencyKey", idempotencyKey?.value)
            put("exceptionType", exception.javaClass.simpleName)
            put("exceptionMessage", exception.message)
            providerException?.providerError?.let { error ->
                put("upstreamStatus", error.upstreamStatus)
                put("retryable", error.retryable)
                put("providerCode", error.code)
                put("providerRequestId", error.trace.providerRequestId)
                put("providerOperationId", error.trace.providerOperationId)
                put("rawProviderStatus", error.trace.rawStatus)
            }
        }.filterValues { it != null }
    }
}
