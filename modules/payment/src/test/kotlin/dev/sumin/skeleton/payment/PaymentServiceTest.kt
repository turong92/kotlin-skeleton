package dev.sumin.skeleton.payment

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PaymentServiceTest {
    @Test
    fun `confirm routes request to the selected provider`() {
        val toss = StubProvider("toss")
        val service = PaymentService(
            router = PaymentProviderRouter(
                providers = listOf(toss, StubProvider("stripe")),
                properties = PaymentProperties(providers = mapOf("toss" to PaymentProperties.Provider(currencies = setOf("KRW")))),
            ),
        )
        val request = PaymentConfirmRequest("pay_1", PaymentAmount(30_000, "KRW"), merchantReferenceId = "order_1", idempotencyKey = IdempotencyKey("idem-1"))

        assertEquals("toss", service.confirm(request).provider)
        assertEquals(request, toss.confirmed.single())
    }

    @Test
    fun `provider failures are published to configured failure handlers before rethrowing`() {
        val failure = PaymentProviderException(
            providerError = PaymentProviderError("toss", "UPSTREAM_ERROR", "provider failed", ProviderTrace("toss", providerRequestId = "req_1"), 502, retryable = true),
            clientName = "payment-toss",
            method = "POST",
            uri = URI.create("https://provider.example.test/payments"),
        )
        val events = mutableListOf<PaymentFailureEvent>()
        val service = PaymentService(
            router = PaymentProviderRouter(listOf(StubProvider("toss", failure)), PaymentProperties(defaultProvider = "toss")),
            failureHandlers = listOf(PaymentFailureHandler { events += it }),
        )

        val thrown = assertFailsWith<PaymentProviderException> {
            service.refund(PaymentRefundRequest("pay_1", PaymentAmount(1_000, "KRW"), reason = "duplicate", idempotencyKey = IdempotencyKey("refund-idem")))
        }

        assertSame(failure, thrown)
        assertEquals(PaymentOperation.REFUND, events.single().operation)
        assertSame(failure, events.single().exception)
    }

    private class StubProvider(override val providerId: String, private val failure: RuntimeException? = null) : PaymentProvider {
        val confirmed = mutableListOf<PaymentConfirmRequest>()

        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
            failure?.let { throw it }
            confirmed += request
            return result(PaymentOperationStatus.CONFIRMED, request.providerPaymentId, request.amount)
        }

        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult = failure?.let { throw it } ?: result(PaymentOperationStatus.CANCELED, request.providerPaymentId, request.amount)

        override fun refund(request: PaymentRefundRequest): PaymentOperationResult = failure?.let { throw it } ?: result(PaymentOperationStatus.REFUNDED, request.providerPaymentId, request.amount)

        private fun result(status: PaymentOperationStatus, providerPaymentId: String, amount: PaymentAmount?) =
            PaymentOperationResult(providerId, providerPaymentId, status, amount, ProviderTrace(providerId))
    }
}
