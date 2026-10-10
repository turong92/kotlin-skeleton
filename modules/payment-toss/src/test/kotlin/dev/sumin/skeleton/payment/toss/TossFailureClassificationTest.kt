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
        // 자동결제 — 2026-10-09 진짜 토스에서 잰 거절(카드 등록 값 없음 · 빌링키 없음). 어느 쪽도 돈이 움직이지 않았다
        404 to "NOT_FOUND_BILLING",
        400 to "INVALID_BILL_KEY_REQUEST",
    )

    @Test
    fun `codes on the allow list - Toss refused and nothing moved - are definitive`() {
        allowList.forEach { (status, code) ->
            assertEquals(PaymentFailureOutcome.DEFINITIVE, outcomeOfRefundReplying(TossFixtures.error(status, code)), "$status $code")
        }
    }

    @Test
    fun `codes where the money may have moved or the outcome is not known are unknown even though they are 4xx`() {
        listOf(
            400 to "ALREADY_PROCESSED_PAYMENT",
            400 to "ALREADY_CANCELED_PAYMENT",
            403 to "NOT_CANCELABLE_AMOUNT",
            403 to "NOT_CANCELABLE_PAYMENT",
            403 to "EXCEED_CANCEL_LIMIT",
            400 to "REFUND_REJECTED",
            409 to "IDEMPOTENT_REQUEST_PROCESSING",
            400 to "PROVIDER_ERROR",
            500 to "FAILED_INTERNAL_SYSTEM_PROCESSING",
            429 to "TOO_MANY_REQUESTS",
            503 to "ANYTHING",
        ).forEach { (status, code) ->
            assertEquals(PaymentFailureOutcome.UNKNOWN, outcomeOfRefundReplying(TossFixtures.error(status, code)), "$status $code")
        }
    }

    @Test
    fun `an allow-listed code that arrives with a 5xx status is unknown`() {
        assertEquals(PaymentFailureOutcome.UNKNOWN, outcomeOfRefundReplying(TossFixtures.error(502, "REJECT_CARD_PAYMENT")))
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
    fun `a dropped connection is a network error and unknown, a POST on a first or reused connection reaches the server once`() {
        server.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Drop)
        val error = assertFailsWith<Exception> { refund() }
        assertIs<ExternalHttpNetworkException>(error)
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error))

        server.clearRequests()
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
