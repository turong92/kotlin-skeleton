package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpResponse
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
import org.springframework.http.HttpHeaders
import org.springframework.util.MultiValueMap
import reactor.core.publisher.Mono

class TossPaymentProviderTest {
    @Test
    fun `confirm posts configurable Toss payload with auth and idempotency headers`() {
        val client = RecordingExternalHttpClient(
            response = TossPaymentResponse(
                paymentKey = "pay_1",
                orderId = "order_1",
                status = "DONE",
                totalAmount = 30_000,
                currency = "KRW",
                traceId = "trace-toss-1",
                transactionKey = "tx_1",
            ),
        )
        val provider = TossPaymentProvider(
            httpClient = client,
            properties = TossPaymentProperties(
                enabled = true,
                secretKey = "test_sk_123",
                baseUrl = "https://payments.example.test",
                confirmPath = "/custom/confirm",
            ),
        )

        val result = provider.confirm(
            PaymentConfirmRequest(
                providerPaymentId = "pay_1",
                merchantReferenceId = "order_1",
                amount = PaymentAmount(amount = 30_000, currency = "KRW"),
                idempotencyKey = IdempotencyKey("idem-confirm"),
                providerPayload = mapOf("metadata" to mapOf("tenant" to "demo")),
            ),
        )

        assertEquals("payment-toss", client.calls.single().clientName)
        assertEquals("/custom/confirm", client.calls.single().path)
        assertEquals(
            mapOf(
                "paymentKey" to "pay_1",
                "orderId" to "order_1",
                "amount" to 30_000L,
                "metadata" to mapOf("tenant" to "demo"),
            ),
            client.calls.single().body,
        )
        assertEquals("Basic dGVzdF9za18xMjM6", client.calls.single().headers["Authorization"]?.single())
        assertEquals("idem-confirm", client.calls.single().headers["Idempotency-Key"]?.single())
        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        assertEquals("trace-toss-1", result.trace.providerRequestId)
        assertEquals("tx_1", result.providerOperationId)
    }

    @Test
    fun `confirm uses Toss trace header when response body has no trace id`() {
        val client = RecordingExternalHttpClient(
            response = TossPaymentResponse(
                paymentKey = "pay_1",
                status = "DONE",
                totalAmount = 30_000,
                currency = "KRW",
                transactionKey = "tx_1",
            ),
            responseHeaders = HttpHeaders().apply {
                add("X-Toss-Trace-Id", "trace-header-1")
            },
        )
        val provider = TossPaymentProvider(
            httpClient = client,
            properties = TossPaymentProperties(
                enabled = true,
                secretKey = "test_sk_123",
            ),
        )

        val result = provider.confirm(
            PaymentConfirmRequest(
                providerPaymentId = "pay_1",
                amount = PaymentAmount(amount = 30_000, currency = "KRW"),
            ),
        )

        assertEquals("trace-header-1", result.trace.providerRequestId)
    }

    @Test
    fun `cancel and refund use cancel endpoint with optional amount`() {
        val client = RecordingExternalHttpClient(
            response = TossPaymentResponse(
                paymentKey = "pay_1",
                status = "CANCELED",
                currency = "KRW",
                lastTransactionKey = "cancel_tx_1",
                cancels = listOf(TossCancel(transactionKey = "cancel_tx_1", cancelAmount = 1_000, canceledAt = "2026-10-08T10:15:30+09:00", cancelStatus = "DONE")),
            ),
        )
        val provider = TossPaymentProvider(
            httpClient = client,
            properties = TossPaymentProperties(
                enabled = true,
                secretKey = "test_sk_123",
                cancelPath = "/custom/payments/{paymentKey}/cancel",
            ),
        )

        val cancel = provider.cancel(
            PaymentCancelRequest(
                providerPaymentId = "pay_1",
                amount = PaymentAmount(amount = 1_000, currency = "KRW"),
                reason = "requested_by_customer",
                idempotencyKey = IdempotencyKey("idem-cancel"),
            ),
        )
        val refund = provider.refund(
            PaymentRefundRequest(
                providerPaymentId = "pay_1",
                amount = PaymentAmount(amount = 1_000, currency = "KRW"),
                reason = "duplicate",
                idempotencyKey = IdempotencyKey("idem-refund"),
            ),
        )

        assertEquals("/custom/payments/{paymentKey}/cancel", client.calls[0].path)
        assertEquals("pay_1", client.calls[0].uriVariables["paymentKey"])
        assertEquals(
            mapOf(
                "cancelReason" to "requested_by_customer",
                "cancelAmount" to 1_000L,
                "currency" to "KRW",
            ),
            client.calls[0].body,
        )
        assertEquals("idem-cancel", client.calls[0].headers["Idempotency-Key"]?.single())
        assertEquals("duplicate", (client.calls[1].body as Map<*, *>)["cancelReason"])
        assertEquals("idem-refund", client.calls[1].headers["Idempotency-Key"]?.single())
        assertEquals(PaymentOperationStatus.CANCELED, cancel.status)
        assertEquals(PaymentOperationStatus.REFUNDED, refund.status)
    }

    @Test
    fun `refund reads the response status instead of always saying refunded`() {
        fun refundStatusFor(providerStatus: String?): PaymentOperationStatus {
            val client = RecordingExternalHttpClient(
                response = TossPaymentResponse(
                    paymentKey = "pay_1",
                    status = providerStatus,
                    currency = "KRW",
                    lastTransactionKey = "tx",
                    cancels = listOf(TossCancel(transactionKey = "tx", cancelAmount = 1_000, canceledAt = "2026-10-08T10:15:30+09:00", cancelStatus = "DONE")),
                ),
            )
            val provider = TossPaymentProvider(
                httpClient = client,
                properties = TossPaymentProperties(enabled = true, secretKey = "test_sk_123"),
            )
            return provider.refund(
                PaymentRefundRequest(
                    providerPaymentId = "pay_1",
                    amount = PaymentAmount(amount = 1_000, currency = "KRW"),
                    idempotencyKey = IdempotencyKey("rfd_1"),
                ),
            ).status
        }

        assertEquals(PaymentOperationStatus.REFUNDED, refundStatusFor("CANCELED"))
        assertEquals(PaymentOperationStatus.REFUNDED, refundStatusFor("PARTIAL_CANCELED"))
        assertEquals(PaymentOperationStatus.FAILED, refundStatusFor("ABORTED"))
        assertEquals(PaymentOperationStatus.FAILED, refundStatusFor("EXPIRED"))
        assertEquals(PaymentOperationStatus.PENDING, refundStatusFor("IN_PROGRESS"))
        assertEquals(PaymentOperationStatus.UNKNOWN, refundStatusFor("DONE"), "DONE after a cancel call means nothing was refunded as far as the response shows - not a success")
        assertEquals(PaymentOperationStatus.UNKNOWN, refundStatusFor(null))
        assertEquals(PaymentOperationStatus.UNKNOWN, refundStatusFor("SOMETHING_NEW"))
    }

    @Test
    fun `provider error exposes the code only - no provider message and no raw body`() {
        val pii = """{"code":"REJECT_CARD_PAYMENT","message":"홍길동 hong@example.test 010-1234-5678 4330123412341234","traceId":"trace-1","customerName":"홍길동"}"""
        val exception = TossPaymentErrorMapper(providerId = "toss").map(
            providerErrorContext(clientName = "payment-toss", upstreamStatus = 403, body = pii),
        )

        assertEquals("REJECT_CARD_PAYMENT", exception.providerError.code)
        assertEquals(null, exception.providerError.message)
        assertEquals(null, exception.providerError.rawBody)
        val everywhere = listOf(exception.message, exception.providerError.toString(), exception.data.toString()).joinToString("|")
        listOf("홍길동", "hong@example.test", "010-1234-5678", "4330123412341234").forEach {
            assertEquals(false, everywhere.contains(it), "leaked $it: $everywhere")
        }
    }

    @Test
    fun `provider error body maps to stable payment exception`() {
        val mapper = TossPaymentErrorMapper(providerId = "toss")
        val exception = mapper.map(
            providerErrorContext(
                clientName = "payment-toss",
                upstreamStatus = 400,
                body = """{"code":"INVALID_REQUEST","message":"invalid amount","traceId":"trace-error-1"}""",
            ),
        )

        val paymentException = assertFailsWith<PaymentProviderException> {
            throw exception
        }
        assertEquals("toss", paymentException.providerError.provider)
        assertEquals("INVALID_REQUEST", paymentException.providerError.code)
        assertEquals(null, paymentException.providerError.message, "the provider's message text is never carried - the code is enough")
        assertEquals("trace-error-1", paymentException.providerError.trace.providerRequestId)
        assertEquals(400, paymentException.providerError.upstreamStatus)
    }

    @Test
    fun `provider error maps Toss trace header when body has no trace id`() {
        val mapper = TossPaymentErrorMapper(providerId = "toss")
        val exception = mapper.map(
            providerErrorContext(
                clientName = "payment-toss",
                upstreamStatus = 500,
                body = """{"code":"FAILED","message":"provider failed"}""",
                headers = HttpHeaders().apply {
                    add("X-Toss-Trace-Id", "trace-error-header-1")
                },
            ),
        )

        assertEquals("trace-error-header-1", exception.providerError.trace.providerRequestId)
    }

    @Test
    fun `provider error maps the trace header the real Toss sends - x-tosspayments-trace-id`() {
        // 2026-10-09 실측: 진짜 토스의 오류 응답(404 · 401)은 추적 번호를 본문이 아니라 헤더 `x-tosspayments-trace-id` 로만 준다. (성공 응답은 재지 않았다. 아래 값은 지어낸 것이다.)
        val mapper = TossPaymentErrorMapper(providerId = "toss")
        val exception = mapper.map(
            providerErrorContext(
                clientName = "payment-toss",
                upstreamStatus = 404,
                body = """{"code":"NOT_FOUND_PAYMENT","message":"not found"}""",
                headers = HttpHeaders().apply {
                    add("x-tosspayments-trace-id", "0123456789abcdef0123456789abcdef")
                },
            ),
        )

        assertEquals("0123456789abcdef0123456789abcdef", exception.providerError.trace.providerRequestId)
    }

    private class RecordingExternalHttpClient(
        private val response: Any,
        private val responseHeaders: HttpHeaders = HttpHeaders(),
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
        ): Mono<T> {
            val spec = ExternalHttpRequestSpec().apply(customize)
            calls += RecordedCall(
                clientName = clientName,
                path = path,
                body = body,
                headers = spec.headersForTest(),
                uriVariables = spec.uriVariablesForTest(),
            )
            return Mono.just(responseType.cast(response))
        }

        override fun <T : Any> postResponse(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<ExternalHttpResponse<T>> {
            val spec = ExternalHttpRequestSpec().apply(customize)
            calls += RecordedCall(
                clientName = clientName,
                path = path,
                body = body,
                headers = spec.headersForTest(),
                uriVariables = spec.uriVariablesForTest(),
            )
            return Mono.just(
                ExternalHttpResponse(
                    statusCode = 200,
                    headers = responseHeaders,
                    body = responseType.cast(response),
                ),
            )
        }

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = Mono.error(UnsupportedOperationException())

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
        val body: Any?,
        val headers: Map<String, List<String>>,
        val uriVariables: Map<String, Any>,
    )
}
