package dev.sumin.skeleton.payment

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PaymentServiceTest {
    @Test
    fun `confirm routes request to the selected provider`() {
        val toss = RecordingPaymentProvider("toss")
        val service = PaymentService(
            router = PaymentProviderRouter(
                providers = listOf(toss, RecordingPaymentProvider("stripe")),
                properties = PaymentProperties(
                    providers = mapOf(
                        "toss" to PaymentProperties.Provider(currencies = setOf("KRW")),
                    ),
                ),
            ),
        )
        val request = PaymentConfirmRequest(
            providerPaymentId = "pay_1",
            merchantReferenceId = "order_1",
            amount = PaymentAmount(amount = 30_000, currency = "KRW"),
            idempotencyKey = IdempotencyKey("idem-1"),
        )

        val result = service.confirm(request)

        assertEquals("toss", result.provider)
        assertEquals(request, toss.confirmed.single())
    }

    @Test
    fun `cancel and refund preserve idempotency key and provider payload`() {
        val stripe = RecordingPaymentProvider("stripe")
        val service = PaymentService(
            router = PaymentProviderRouter(
                providers = listOf(stripe),
                properties = PaymentProperties(defaultProvider = "stripe"),
            ),
        )

        val cancel = PaymentCancelRequest(
            providerPaymentId = "pi_1",
            reason = "requested_by_customer",
            idempotencyKey = IdempotencyKey("cancel-idem"),
            providerPayload = mapOf("memo" to "customer asked"),
        )
        val refund = PaymentRefundRequest(
            providerPaymentId = "pi_1",
            amount = PaymentAmount(amount = 1_000, currency = "USD"),
            reason = "duplicate",
            idempotencyKey = IdempotencyKey("refund-idem"),
            providerPayload = mapOf("expand[]" to "charge"),
        )

        service.cancel(cancel)
        service.refund(refund)

        assertEquals(cancel, stripe.canceled.single())
        assertEquals(refund, stripe.refunded.single())
    }

    @Test
    fun `provider failures are published to configured failure handlers before rethrowing`() {
        val failure = paymentProviderException(provider = "toss")
        val provider = FailingPaymentProvider(providerId = "toss", failure = failure)
        val events = mutableListOf<PaymentFailureEvent>()
        val service = PaymentService(
            router = PaymentProviderRouter(
                providers = listOf(provider),
                properties = PaymentProperties(defaultProvider = "toss"),
            ),
            failureHandlers = listOf(PaymentFailureHandler { events += it }),
        )
        val request = PaymentRefundRequest(
            providerPaymentId = "pay_1",
            amount = PaymentAmount(amount = 1_000, currency = "KRW"),
            reason = "duplicate",
            idempotencyKey = IdempotencyKey("refund-idem"),
        )

        val thrown = assertFailsWith<PaymentProviderException> {
            service.refund(request)
        }

        val event = events.single()
        assertSame(failure, thrown)
        assertEquals(PaymentOperation.REFUND, event.operation)
        assertEquals("toss", event.provider)
        assertEquals("pay_1", event.providerPaymentId)
        assertEquals(PaymentAmount(amount = 1_000, currency = "KRW"), event.amount)
        assertEquals("refund-idem", event.idempotencyKey?.value)
        assertSame(failure, event.exception)
    }

    private class RecordingPaymentProvider(
        override val providerId: String,
    ) : PaymentProvider {
        val confirmed = mutableListOf<PaymentConfirmRequest>()
        val canceled = mutableListOf<PaymentCancelRequest>()
        val refunded = mutableListOf<PaymentRefundRequest>()

        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
            confirmed += request
            return result(PaymentOperationStatus.CONFIRMED, request.providerPaymentId, request.amount)
        }

        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult {
            canceled += request
            return result(PaymentOperationStatus.CANCELED, request.providerPaymentId, request.amount)
        }

        override fun refund(request: PaymentRefundRequest): PaymentOperationResult {
            refunded += request
            return result(PaymentOperationStatus.REFUNDED, request.providerPaymentId, request.amount)
        }

        private fun result(
            status: PaymentOperationStatus,
            providerPaymentId: String,
            amount: PaymentAmount?,
        ): PaymentOperationResult =
            PaymentOperationResult(
                provider = providerId,
                providerPaymentId = providerPaymentId,
                status = status,
                amount = amount,
                trace = ProviderTrace(provider = providerId),
            )
    }

    private class FailingPaymentProvider(
        override val providerId: String,
        private val failure: RuntimeException,
    ) : PaymentProvider {
        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult = throw failure

        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult = throw failure

        override fun refund(request: PaymentRefundRequest): PaymentOperationResult = throw failure
    }

    private fun paymentProviderException(provider: String): PaymentProviderException =
        PaymentProviderException(
            providerError = PaymentProviderError(
                provider = provider,
                code = "UPSTREAM_ERROR",
                message = "provider failed",
                trace = ProviderTrace(provider = provider, providerRequestId = "req_1"),
                upstreamStatus = 502,
                retryable = true,
            ),
            clientName = "payment-$provider",
            method = "POST",
            uri = URI.create("https://provider.example.test/payments"),
        )
}
