package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpNetworkException
import dev.sumin.skeleton.common.http.ExternalHttpTimeoutException
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.tossProvider
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 확정(허용 목록의 거절 코드만) / 모름(그 밖의 전부 — 타임아웃 · 5xx · 네트워크 · 해석 불가 · 목록에 없는 코드) — 헷갈리면 모름이다. */
class TossFailureClassificationTest {
    private val server = FakeTossServer()
    private val provider = tossProvider(server)

    @AfterTest
    fun tearDown() = server.close()

    private fun refund() =
        provider.refund(
            PaymentRefundRequest("pay_key_1", PaymentAmount(1_000, "KRW"), "고객 요청", idempotencyKey = IdempotencyKey("rfd_1")),
        )

    private fun confirm() =
        provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))

    private fun outcomeOfRefundReplying(reply: Reply): PaymentFailureOutcome {
        server.on("POST", "/v1/payments/pay_key_1/cancel", reply)
        return PaymentFailures.outcomeOf(assertFailsWith<Exception> { refund() })
    }

    /** 허용 목록 전체 — 코드를 더하거나 빼면 이 집합과 [codes on the allow list] 시험이 함께 깨져서 눈에 띈다. */
    private val allowList: List<Pair<Int, String>> = listOf(
        400 to "INVALID_REQUEST",
        400 to "INVALID_API_KEY",
        401 to "UNAUTHORIZED_KEY",
        403 to "INCORRECT_BASIC_AUTH_FORMAT",
        403 to "FORBIDDEN_REQUEST",
        404 to "NOT_FOUND_PAYMENT",
        404 to "NOT_FOUND_PAYMENT_SESSION",
        403 to "REJECT_CARD_PAYMENT",
        403 to "REJECT_CARD_COMPANY",
        403 to "REJECT_ACCOUNT_PAYMENT",
        400 to "INVALID_REJECT_CARD",
        400 to "INVALID_STOPPED_CARD",
        400 to "INVALID_CARD_LOST_OR_STOLEN",
        400 to "INVALID_CARD_NUMBER",
        400 to "INVALID_CARD_EXPIRATION",
        400 to "EXCEED_MAX_AMOUNT",
        400 to "EXCEED_MAX_PAYMENT_AMOUNT",
        400 to "EXCEED_MAX_DAILY_PAYMENT_COUNT",
        400 to "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
        400 to "BELOW_MINIMUM_AMOUNT",
        403 to "FDS_ERROR",
        403 to "NOT_ALLOWED_PARTIAL_REFUND",
        403 to "NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT",
        403 to "EXCEED_MAX_REFUND_DUE",
    )

    @Test
    fun `codes on the allow list - Toss refused and nothing moved - are definitive`() {
        allowList.forEach { (status, code) ->
            assertEquals(PaymentFailureOutcome.DEFINITIVE, outcomeOfRefundReplying(TossFixtures.error(status, code)), "$status $code")
        }
    }

    @Test
    fun `the allow list is exactly this set - a code added or removed by accident fails here`() {
        assertEquals(24, allowList.size)
        assertEquals(allowList.map { it.second }.toSet(), TossPaymentErrorMapper.DEFINITIVE_CODES)
    }

    @Test
    fun `every allow-listed code is marked definitive on the exception and nothing else is`() {
        server.on("POST", "/v1/payments/confirm", TossFixtures.error(403, "REJECT_CARD_PAYMENT"))
        assertTrue(assertFailsWith<PaymentProviderException> { confirm() }.providerError.definitive)

        server.on("POST", "/v1/payments/confirm", TossFixtures.error(400, "ALREADY_PROCESSED_PAYMENT"))
        assertFalse(assertFailsWith<PaymentProviderException> { confirm() }.providerError.definitive)
    }

    @Test
    fun `codes where the money may have moved or the outcome is not known are unknown even though they are 4xx`() {
        listOf(
            400 to "ALREADY_PROCESSED_PAYMENT",
            400 to "ALREADY_PROCESSING_REQUEST",
            400 to "ALREADY_CANCELED_PAYMENT",
            400 to "ALREADY_REFUND_PAYMENT",
            // 환불 쪽에서도: 앞선 환불이 잔액 · 상태 · 횟수를 이미 바꿨기 때문에 나올 수 있는 거절은 확정이 아니다(이중 환불 방지)
            403 to "NOT_CANCELABLE_AMOUNT",
            403 to "NOT_CANCELABLE_PAYMENT",
            403 to "EXCEED_CANCEL_LIMIT",
            // 공식 설명이 「결제사에 문의」뿐이라 앞선 환불이 적용됐을 가능성을 배제할 근거가 없다
            400 to "REFUND_REJECTED",
            409 to "IDEMPOTENT_REQUEST_PROCESSING",
            403 to "FORBIDDEN_CONSECUTIVE_REQUEST",
            400 to "PROVIDER_ERROR",
            400 to "CARD_PROCESSING_ERROR",
            400 to "NOT_MATCHES_REFUNDABLE_AMOUNT",
            400 to "UNAPPROVED_ORDER_ID",
            500 to "FAILED_INTERNAL_SYSTEM_PROCESSING",
            500 to "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",
            500 to "UNKNOWN_PAYMENT_ERROR",
            500 to "FAILED_REFUND_PROCESS",
            429 to "TOO_MANY_REQUESTS",
            503 to "ANYTHING",
        ).forEach { (status, code) ->
            assertEquals(PaymentFailureOutcome.UNKNOWN, outcomeOfRefundReplying(TossFixtures.error(status, code)), "$status $code")
        }
    }

    @Test
    fun `a 4xx code that is not on the allow list is unknown - including one Toss adds tomorrow`() {
        listOf(
            400 to "A_CODE_ADDED_NEXT_YEAR",
            403 to "REJECT_SOMETHING_NEW",
            404 to "NOT_FOUND_SOMETHING",
            422 to "UNPROCESSABLE",
        ).forEach { (status, code) ->
            assertEquals(PaymentFailureOutcome.UNKNOWN, outcomeOfRefundReplying(TossFixtures.error(status, code)), "$status $code")
        }
    }

    @Test
    fun `an allow-listed code that arrives with a 5xx status is unknown`() {
        assertEquals(PaymentFailureOutcome.UNKNOWN, outcomeOfRefundReplying(TossFixtures.error(502, "REJECT_CARD_PAYMENT")))
    }

    @Test
    fun `PROVIDER_ERROR is marked retryable on the exception`() {
        server.on("POST", "/v1/payments/confirm", TossFixtures.error(400, "PROVIDER_ERROR"))

        val error = assertFailsWith<PaymentProviderException> { confirm() }

        assertTrue(error.providerError.retryable)
        assertEquals("PROVIDER_ERROR", error.providerError.code)
    }

    @Test
    fun `an answer that is not Toss-shaped is unknown - html from a proxy, an empty body, junk`() {
        listOf(
            Reply.Json(502, "<html>Bad Gateway</html>"),
            Reply.Json(400, ""),
            Reply.Json(403, "not json at all"),
            Reply.Json(400, """{"code":"lowercase_code_is_not_a_toss_code","message":"x"}"""),
        ).forEach { reply ->
            assertEquals(PaymentFailureOutcome.UNKNOWN, outcomeOfRefundReplying(reply), reply.toString())
        }
    }

    @Test
    fun `a 200 with a broken body is unknown - the money may have moved`() {
        assertEquals(PaymentFailureOutcome.UNKNOWN, outcomeOfRefundReplying(Reply.Broken()))
        server.on("POST", "/v1/payments/confirm", Reply.Broken())
        val error = assertFailsWith<PaymentProviderException> { confirm() }
        assertEquals("UNREADABLE_RESPONSE", error.providerError.code)
        assertEquals(200, error.providerError.upstreamStatus)
    }

    @Test
    fun `a dropped connection is a network error and unknown`() {
        server.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Drop)

        val error = assertFailsWith<Exception> { refund() }

        assertIs<ExternalHttpNetworkException>(error)
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error))
    }

    @Test
    fun `a POST whose first connection is dropped without an answer reaches the server once`() {
        server.on("POST", "/v1/payments/confirm", Reply.Drop)

        assertFailsWith<ExternalHttpNetworkException> { confirm() }

        assertEquals(1, server.requests.size, "requests the server saw: ${server.requests.map { it.method + " " + it.path }}")
    }

    @Test
    fun `a POST dropped on a connection that was already used reaches the server once`() {
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))
        confirm()
        server.clearRequests()
        server.on("POST", "/v1/payments/confirm", Reply.Drop)

        assertFailsWith<ExternalHttpNetworkException> { confirm() }

        assertEquals(1, server.requests.size, "requests the server saw: ${server.requests.map { it.method + " " + it.path }}")
    }

    @Test
    fun `a response that never comes is a timeout, unknown, and the request was sent once`() {
        val impatient = tossProvider(server, responseTimeout = Duration.ofMillis(300))
        server.on("POST", "/v1/payments/confirm", Reply.Hang)

        val error = assertFailsWith<Exception> {
            impatient.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))
        }

        assertIs<ExternalHttpTimeoutException>(error)
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `an exception that came from an unreadable answer has no cause that could carry the body`() {
        server.on("POST", "/v1/payments/confirm", Reply.Broken())

        val error = assertFailsWith<PaymentProviderException> { confirm() }

        assertFalse(error.cause != null, "the decode exception (which may quote the body) is not chained")
    }
}
