package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.http.ExternalHttpException
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
    @TestFactory
    fun `classifies a failure as definitive or unknown`(): List<DynamicTest> =
        classification().map { case ->
            DynamicTest.dynamicTest(case.name) {
                assertEquals(case.expected, PaymentFailures.outcomeOf(case.exception), case.name)
            }
        }

    @Test
    fun `an exception hidden under a wrapper is classified by its cause`() {
        val wrapped = RuntimeException("wrapper", providerException(status = 400, code = "REJECT_CARD_COMPANY", definitive = true))

        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(wrapped))
        assertEquals(
            PaymentFailureOutcome.UNKNOWN,
            PaymentFailures.outcomeOf(RuntimeException("wrapper", timeout())),
        )
    }

    @Test
    fun `a definitive answer buried deeper than the depth limit is read as unknown`() {
        var chain: Throwable = providerException(status = 403, code = "REJECT_CARD_PAYMENT", definitive = true)
        repeat(9) { chain = RuntimeException("wrapper", chain) }
        assertEquals(PaymentFailureOutcome.DEFINITIVE, PaymentFailures.outcomeOf(chain), "ten levels deep is still read")

        repeat(5) { chain = RuntimeException("wrapper", chain) }
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(chain), "past the limit it is not read - unknown is the safe side")
    }

    @Test
    fun `a cause cycle ends as unknown instead of looping`() {
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
            case("401 the module marked as a refusal", providerException(401, "UNAUTHORIZED_KEY", definitive = true), PaymentFailureOutcome.DEFINITIVE),
            case("403 the module marked as a refusal", providerException(403, definitive = true), PaymentFailureOutcome.DEFINITIVE),
            case("404 the module marked as a refusal", providerException(404, "NOT_FOUND_PAYMENT", definitive = true), PaymentFailureOutcome.DEFINITIVE),
            case("409 the module marked as a refusal", providerException(409, definitive = true), PaymentFailureOutcome.DEFINITIVE),
            case("422 the module marked as a refusal", providerException(422, definitive = true), PaymentFailureOutcome.DEFINITIVE),
            case("400 with a code but no refusal mark", providerException(400), PaymentFailureOutcome.UNKNOWN),
            case("401 with a code but no refusal mark", providerException(401, "UNAUTHORIZED_KEY"), PaymentFailureOutcome.UNKNOWN),
            case("403 with a code but no refusal mark", providerException(403), PaymentFailureOutcome.UNKNOWN),
            case("404 with a code but no refusal mark", providerException(404, "NOT_FOUND_PAYMENT"), PaymentFailureOutcome.UNKNOWN),
            case("409 IDEMPOTENT_REQUEST_PROCESSING - an earlier request is still running", providerException(409, "IDEMPOTENT_REQUEST_PROCESSING"), PaymentFailureOutcome.UNKNOWN),
            case("400 ALREADY_PROCESSED_PAYMENT - on a confirm the money already moved", providerException(400, "ALREADY_PROCESSED_PAYMENT"), PaymentFailureOutcome.UNKNOWN),
            case("400 ALREADY_CANCELED_PAYMENT - on a refund it is already refunded", providerException(400, "ALREADY_CANCELED_PAYMENT"), PaymentFailureOutcome.UNKNOWN),
            case("a 4xx code nobody listed", providerException(400, "A_CODE_THE_MODULE_NEVER_HEARD_OF"), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark on a 5xx", providerException(500, definitive = true), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark on a 429", providerException(429, definitive = true), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark on a 408", providerException(408, definitive = true), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark without a code", providerException(400, code = null, definitive = true), PaymentFailureOutcome.UNKNOWN),
            case("refusal mark that is also retryable (contradiction)", providerException(400, definitive = true, retryable = true), PaymentFailureOutcome.UNKNOWN),
            case("4xx marked retryable (provider says transient)", providerException(400, "PROVIDER_ERROR", retryable = true), PaymentFailureOutcome.UNKNOWN),
            case("429 rate limited", providerException(429, "TOO_MANY_REQUESTS", retryable = true), PaymentFailureOutcome.UNKNOWN),
            case("429 even when not marked retryable", providerException(429), PaymentFailureOutcome.UNKNOWN),
            case("408 request timeout", providerException(408), PaymentFailureOutcome.UNKNOWN),
            case("4xx without a code (cannot interpret)", providerException(400, code = null), PaymentFailureOutcome.UNKNOWN),
            case("4xx with a blank code", providerException(400, code = "  "), PaymentFailureOutcome.UNKNOWN),
            case("500", providerException(500, retryable = true), PaymentFailureOutcome.UNKNOWN),
            case("502 even if not marked retryable", providerException(502), PaymentFailureOutcome.UNKNOWN),
            case("503 with a code", providerException(503, "UNAVAILABLE"), PaymentFailureOutcome.UNKNOWN),
            case("2xx upstream status on an error", providerException(200), PaymentFailureOutcome.UNKNOWN),
            case("3xx upstream status", providerException(302), PaymentFailureOutcome.UNKNOWN),
            case("untranslated 4xx from the http layer", ExternalHttpStatusException("pay", "POST", uri, 400, "{}"), PaymentFailureOutcome.UNKNOWN),
            case("untranslated 5xx from the http layer", ExternalHttpStatusException("pay", "POST", uri, 500, "{}"), PaymentFailureOutcome.UNKNOWN),
            case("external exception without a status", ExternalHttpException("m", "pay", "POST", uri, null, false, errorCode = PaymentErrorCode.PROVIDER_ERROR), PaymentFailureOutcome.UNKNOWN),
            case("unrelated exception", IllegalStateException("boom"), PaymentFailureOutcome.UNKNOWN),
        )
    }
}
