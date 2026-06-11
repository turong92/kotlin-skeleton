package dev.sumin.skeleton.payment.stripe

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCancelRequest
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentRefundRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import reactor.core.publisher.Mono

class StripePaymentProviderTest {
    @Test
    fun `confirm posts to configurable payment intent endpoint with auth and idempotency headers`() {
        val client = RecordingExternalHttpClient(
            response = StripePaymentIntentResponse(
                id = "pi_1",
                status = "succeeded",
                amount = 2_500,
                currency = "usd",
                latestCharge = "ch_1",
                requestId = "req_1",
            ),
        )
        val provider = StripePaymentProvider(
            httpClient = client,
            properties = StripePaymentProperties(
                enabled = true,
                secretKey = "sk_test_123",
                confirmPath = "/custom/payment_intents/{paymentIntentId}/confirm",
            ),
        )

        val result = provider.confirm(
            PaymentConfirmRequest(
                providerPaymentId = "pi_1",
                amount = PaymentAmount(amount = 2_500, currency = "USD"),
                idempotencyKey = IdempotencyKey("idem-confirm"),
                providerPayload = mapOf("payment_method" to "pm_card_visa"),
            ),
        )

        assertEquals("payment-stripe", client.calls.single().clientName)
        assertEquals("/custom/payment_intents/{paymentIntentId}/confirm", client.calls.single().path)
        assertEquals("pi_1", client.calls.single().uriVariables["paymentIntentId"])
        assertEquals(mapOf("payment_method" to "pm_card_visa"), client.calls.single().form)
        assertEquals("Bearer sk_test_123", client.calls.single().headers["Authorization"]?.single())
        assertEquals("idem-confirm", client.calls.single().headers["Idempotency-Key"]?.single())
        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        assertEquals("req_1", result.trace.providerRequestId)
        assertEquals("ch_1", result.providerOperationId)
    }

    @Test
    fun `cancel and refund use Stripe form bodies`() {
        val client = RecordingExternalHttpClient(
            responses = ArrayDeque(
                listOf(
                    StripePaymentIntentResponse(
                        id = "pi_1",
                        status = "canceled",
                        amount = 2_500,
                        currency = "usd",
                    ),
                    StripeRefundResponse(
                        id = "re_1",
                        paymentIntent = "pi_1",
                        status = "succeeded",
                        amount = 1_000,
                        currency = "usd",
                        requestId = "req_refund",
                    ),
                ),
            ),
        )
        val provider = StripePaymentProvider(
            httpClient = client,
            properties = StripePaymentProperties(
                enabled = true,
                secretKey = "sk_test_123",
                cancelPath = "/custom/payment_intents/{paymentIntentId}/cancel",
                refundPath = "/custom/refunds",
            ),
        )

        val cancel = provider.cancel(
            PaymentCancelRequest(
                providerPaymentId = "pi_1",
                reason = "requested_by_customer",
                idempotencyKey = IdempotencyKey("idem-cancel"),
            ),
        )
        val refund = provider.refund(
            PaymentRefundRequest(
                providerPaymentId = "pi_1",
                amount = PaymentAmount(amount = 1_000, currency = "USD"),
                reason = "duplicate",
                idempotencyKey = IdempotencyKey("idem-refund"),
            ),
        )

        assertEquals("/custom/payment_intents/{paymentIntentId}/cancel", client.calls[0].path)
        assertEquals(mapOf("cancellation_reason" to "requested_by_customer"), client.calls[0].form)
        assertEquals("idem-cancel", client.calls[0].headers["Idempotency-Key"]?.single())
        assertEquals("/custom/refunds", client.calls[1].path)
        assertEquals(
            mapOf(
                "payment_intent" to "pi_1",
                "amount" to "1000",
                "reason" to "duplicate",
            ),
            client.calls[1].form,
        )
        assertEquals("idem-refund", client.calls[1].headers["Idempotency-Key"]?.single())
        assertEquals(PaymentOperationStatus.CANCELED, cancel.status)
        assertEquals(PaymentOperationStatus.REFUNDED, refund.status)
        assertEquals("re_1", refund.providerOperationId)
    }

    @Test
    fun `provider error body maps to stable payment exception`() {
        val mapper = StripePaymentErrorMapper(providerId = "stripe")
        val exception = mapper.map(
            providerErrorContext(
                clientName = "payment-stripe",
                upstreamStatus = 402,
                body = """{"error":{"type":"card_error","code":"card_declined","message":"card declined","request_log_url":"https://dashboard.stripe.com/test/logs/req_1"}}""",
            ),
        )

        val paymentException = assertFailsWith<PaymentProviderException> {
            throw exception
        }
        assertEquals("stripe", paymentException.providerError.provider)
        assertEquals("card_declined", paymentException.providerError.code)
        assertEquals("card declined", paymentException.providerError.message)
        assertEquals("card_error", paymentException.providerError.trace.rawCode)
        assertEquals("https://dashboard.stripe.com/test/logs/req_1", paymentException.providerError.trace.metadata["requestLogUrl"])
        assertEquals(402, paymentException.providerError.upstreamStatus)
    }

    private class RecordingExternalHttpClient(
        response: Any? = null,
        private val responses: ArrayDeque<Any> = ArrayDeque(listOfNotNull(response)),
    ) : ExternalHttpClient {
        val calls = mutableListOf<RecordedCall>()

        override fun <T : Any> get(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> post(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> {
            val spec = ExternalHttpRequestSpec().apply(customize)
            calls += RecordedCall(
                clientName = clientName,
                path = path,
                form = form,
                headers = spec.headersForTest(),
                uriVariables = spec.uriVariablesForTest(),
            )
            return Mono.just(responseType.cast(responses.removeFirst()))
        }

        override fun <T : Any> put(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> patch(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

        override fun <T : Any> delete(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())
    }

    private data class RecordedCall(
        val clientName: String,
        val path: String,
        val form: Map<String, String>,
        val headers: Map<String, List<String>>,
        val uriVariables: Map<String, Any>,
    )
}
