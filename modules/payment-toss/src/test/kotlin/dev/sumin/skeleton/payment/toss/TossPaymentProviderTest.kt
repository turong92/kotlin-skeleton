package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpResponse
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.payment.PaymentProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.http.HttpHeaders
import reactor.core.publisher.Mono

class TossPaymentProviderTest {
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
