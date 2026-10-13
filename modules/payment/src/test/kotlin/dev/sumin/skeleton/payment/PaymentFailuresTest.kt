package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.http.ExternalHttpNetworkException
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import dev.sumin.skeleton.common.http.ExternalHttpTimeoutException
import java.io.IOException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class PaymentFailuresTest {
    @Test
    fun `a failure is described by the http status and the provider error code only - never the body or message`() {
        val body = """{"error":{"type":"request_error","code":"transaction_default_checkout_url_not_set","detail":"secret-ish detail","errors":[{"field":"x"}]}}"""
        val failure = RuntimeException("wrapper", ExternalHttpStatusException("paddle", "POST", URI("https://sandbox-api.paddle.com/transactions"), 400, body))
        assertEquals("status=400 code=transaction_default_checkout_url_not_set", PaymentFailures.describe(failure))
        assertEquals("400:transaction_default_checkout_url_not_set", PaymentFailures.eventCodeOf(failure))
        assertEquals("status=503 code=-", PaymentFailures.describe(ExternalHttpStatusException("paddle", "POST", URI("https://x/y"), 503, "<html>down</html>")))
        assertEquals("HTTP_503", PaymentFailures.eventCodeOf(ExternalHttpStatusException("paddle", "POST", URI("https://x/y"), 503, "")))
        assertEquals("IllegalStateException", PaymentFailures.eventCodeOf(IllegalStateException("boom")))
    }

    @TestFactory
    fun `classifies a failure as definitive or unknown`(): List<DynamicTest> =
        classification().map { case ->
            DynamicTest.dynamicTest(case.name) {
                assertEquals(case.expected, PaymentFailures.outcomeOf(case.exception), case.name)
            }
        }

    @Test
    fun `a failure is read through wrappers but only to a depth limit and a cause cycle is unknown - unknown is the safe side`() {
        val refusal = providerException(status = 403, code = "REJECT_CARD_PAYMENT", definitive = true)
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(RuntimeException("wrapper", timeout())))
        var chain: Throwable = refusal
        repeat(9) { chain = RuntimeException("wrapper", chain) }
        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(chain), "ten levels deep is still read")
        repeat(5) { chain = RuntimeException("wrapper", chain) }
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(chain))

        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(a))
    }

    companion object {
        private val uri = URI.create("https://pay.example.test/v1/payments/confirm")

        fun timeout() = ExternalHttpTimeoutException("pay", "POST", uri)

        fun providerException(
            status: Int,
            code: String? = "SOME_CODE",
            retryable: Boolean = false,
            definitive: Boolean = false,
        ) = PaymentProviderException(
            providerError = PaymentProviderError(
                provider = "pay",
                code = code,
                message = "m",
                trace = ProviderTrace(provider = "pay"),
                upstreamStatus = status,
                retryable = retryable,
                definitive = definitive,
            ),
            clientName = "pay",
            method = "POST",
            uri = uri,
        )

        private data class Case(val name: String, val exception: Throwable, val expected: PaymentFailureOutcome)

        private fun case(name: String, exception: Throwable, expected: PaymentFailureOutcome) = Case(name, exception, expected)

        private fun classification(): List<Case> = listOf(
            case("timeout", timeout(), PaymentFailureOutcome.UNKNOWN),
            case("network error", ExternalHttpNetworkException("pay", "POST", uri, IOException("reset")), PaymentFailureOutcome.UNKNOWN),
            case("400 the module marked as a refusal", providerException(400, definitive = true), PaymentFailureOutcome.DEFINITIVE),
            case("404 the module marked as a refusal", providerException(404, "NOT_FOUND_PAYMENT", definitive = true), PaymentFailureOutcome.DEFINITIVE),
            case("400 with a code but no refusal mark", providerException(400), PaymentFailureOutcome.UNKNOWN),
            case("409 IDEMPOTENT_REQUEST_PROCESSING - an earlier request is still running", providerException(409, "IDEMPOTENT_REQUEST_PROCESSING"), PaymentFailureOutcome.UNKNOWN),
            case("400 ALREADY_PROCESSED_PAYMENT - on a confirm the money already moved", providerException(400, "ALREADY_PROCESSED_PAYMENT"), PaymentFailureOutcome.UNKNOWN),
            case("400 ALREADY_CANCELED_PAYMENT - on a refund it is already refunded", providerException(400, "ALREADY_CANCELED_PAYMENT"), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark on a 5xx", providerException(500, definitive = true), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark on a 429", providerException(429, definitive = true), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark that is also retryable (contradiction)", providerException(400, definitive = true, retryable = true), PaymentFailureOutcome.UNKNOWN),
            case("4xx marked retryable (provider says transient)", providerException(400, "PROVIDER_ERROR", retryable = true), PaymentFailureOutcome.UNKNOWN),
            case("untranslated 4xx from the http layer", ExternalHttpStatusException("pay", "POST", uri, 400, "{}"), PaymentFailureOutcome.UNKNOWN),
        )
    }
}
