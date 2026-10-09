package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.tossProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import tools.jackson.databind.json.JsonMapper

/** 승인 · 환불 — 가짜 토스 서버로 요청 모양(인증 · 멱등 키 · 본문 · 경로)과 결과(응답 상태 · 남은 금액 · 거래 키)를 본다. */
class TossConfirmRefundHttpTest {
    private val server = FakeTossServer()
    private val provider = tossProvider(server)
    private val json = JsonMapper.builder().build()

    @AfterTest
    fun tearDown() = server.close()

    private fun refundRequest(amount: Long = 1_000, key: String? = "rfd_1") =
        PaymentRefundRequest(
            providerPaymentId = "pay_key_1",
            amount = PaymentAmount(amount, "KRW"),
            reason = "고객 요청",
            idempotencyKey = key?.let(::IdempotencyKey),
        )

    @Test
    fun `confirm sends paymentKey orderId amount and uses the order id as the idempotency key`() {
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment(lastTransactionKey = "tx_last")))

        val result = provider.confirm(
            PaymentConfirmRequest(
                providerPaymentId = "pay_key_1",
                amount = PaymentAmount(3_000, "KRW"),
                merchantReferenceId = "ord_abcdef123456",
            ),
        )

        val request = server.requests.single()
        assertEquals("/v1/payments/confirm", request.path)
        assertEquals("ord_abcdef123456", request.header("idempotency-key"))
        assertEquals(
            mapOf("paymentKey" to "pay_key_1", "orderId" to "ord_abcdef123456", "amount" to 3000),
            json.readValue(request.body, Map::class.java),
        )
        assertEquals(PaymentOperationStatus.CONFIRMED, result.status)
        assertEquals("tx_last", result.providerOperationId)
        assertEquals(PaymentAmount(3_000, "KRW"), result.amount)
    }

    @Test
    fun `an explicit idempotency key wins over the order id`() {
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))

        provider.confirm(
            PaymentConfirmRequest(
                providerPaymentId = "pay_key_1",
                amount = PaymentAmount(3_000, "KRW"),
                merchantReferenceId = "ord_abcdef123456",
                idempotencyKey = IdempotencyKey("custom-key"),
            ),
        )

        assertEquals("custom-key", server.requests.single().header("idempotency-key"))
    }

    @Test
    fun `a partial refund reports the remaining amount, the cancel transaction key and the refunded amount`() {
        val cancels = listOf(TossFixtures.cancel("tx_older", 500), TossFixtures.cancel("tx_this", 1_000))
        server.on(
            "POST",
            "/v1/payments/pay_key_1/cancel",
            Reply.Json(200, TossFixtures.payment(status = "PARTIAL_CANCELED", total = 3_000, balance = 1_500, cancels = cancels, lastTransactionKey = "tx_this")),
        )

        val result = provider.refund(refundRequest(amount = 1_000))

        val request = server.requests.single()
        assertEquals("/v1/payments/pay_key_1/cancel", request.path)
        assertEquals("rfd_1", request.header("idempotency-key"))
        assertEquals("고객 요청", (json.readValue(request.body, Map::class.java))["cancelReason"])
        assertEquals(1000, (json.readValue(request.body, Map::class.java))["cancelAmount"])
        assertEquals(PaymentOperationStatus.REFUNDED, result.status)
        assertEquals("PARTIAL_CANCELED", result.rawProviderStatus)
        assertEquals(PaymentAmount(1_500, "KRW"), result.remaining)
        assertEquals("tx_this", result.providerOperationId, "the cancel whose transaction key is the payment's last transaction key is this refund")
        assertEquals(PaymentAmount(1_000, "KRW"), result.amount, "the refunded amount, not the payment total")
        assertEquals("true", result.attributes["partial"])
    }

    @Test
    fun `a full refund has nothing left`() {
        server.on(
            "POST",
            "/v1/payments/pay_key_1/cancel",
            Reply.Json(200, TossFixtures.payment(status = "CANCELED", total = 3_000, balance = 0, cancels = listOf(TossFixtures.cancel("tx_all", 3_000)), lastTransactionKey = "tx_all")),
        )

        val result = provider.refund(refundRequest(amount = 3_000))

        assertEquals(PaymentOperationStatus.REFUNDED, result.status)
        assertEquals(PaymentAmount(0, "KRW"), result.remaining)
        assertEquals("tx_all", result.providerOperationId)
        assertNull(result.attributes["partial"])
    }

    @Test
    fun `a refund the response does not show as applied is not reported as refunded`() {
        // 취소 응답의 상태가 DONE 이면 결제가 그대로라는 뜻이다 — 예전에는 이것도 REFUNDED 로 돌려줬다
        server.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Json(200, TossFixtures.payment(status = "DONE", total = 3_000, balance = 3_000, cancels = null)))

        val result = provider.refund(refundRequest())

        assertEquals(PaymentOperationStatus.UNKNOWN, result.status)
        assertEquals(PaymentAmount(3_000, "KRW"), result.remaining)
        assertNull(result.providerOperationId)
    }

    @Test
    fun `the refund call is sent exactly once even when it fails`() {
        server.on("POST", "/v1/payments/pay_key_1/cancel", TossFixtures.error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING"))

        runCatching { provider.refund(refundRequest()) }

        assertEquals(1, server.requests.size, "the client never re-sends by itself")
    }

    private fun refundReplying(body: String): dev.sumin.skeleton.payment.PaymentOperationResult {
        server.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Json(200, body))
        return provider.refund(refundRequest())
    }

    @Test
    fun `the cancel of this call is the one whose transaction key is the payment's last transaction key - not the first or the last in the list`() {
        val cancels = listOf(TossFixtures.cancel("tx_this", 1_000), TossFixtures.cancel("tx_older", 500))

        val result = refundReplying(TossFixtures.payment(status = "PARTIAL_CANCELED", balance = 1_500, cancels = cancels, lastTransactionKey = "tx_this"))

        assertEquals(PaymentOperationStatus.REFUNDED, result.status)
        assertEquals("tx_this", result.providerOperationId)
        assertEquals(PaymentAmount(1_000, "KRW"), result.amount)
    }

    @Test
    fun `an earlier partial refund does not make this refund look done - this cancel still in progress is unknown with no old key or amount`() {
        val cancels = listOf(TossFixtures.cancel("tx_older", 500), TossFixtures.cancel("tx_this", 1_000, status = "IN_PROGRESS"))

        val result = refundReplying(TossFixtures.payment(status = "PARTIAL_CANCELED", balance = 2_500, cancels = cancels, lastTransactionKey = "tx_this"))

        assertEquals(PaymentOperationStatus.UNKNOWN, result.status)
        assertNull(result.providerOperationId, "the older refund's transaction key must not come back as this refund's")
        assertEquals(PaymentAmount(1_000, "KRW"), result.amount, "what was asked, not the older refund's amount")
    }

    @Test
    fun `a payment with no cancel is unknown and its last transaction key - the approval - is not reported as a refund key`() {
        val result = refundReplying(TossFixtures.payment(status = "CANCELED", balance = 0, cancels = null, lastTransactionKey = "tx_approval"))

        assertEquals(PaymentOperationStatus.UNKNOWN, result.status)
        assertNull(result.providerOperationId)
    }

    @Test
    fun `no cancel carries the last transaction key - unknown`() {
        val cancels = listOf(TossFixtures.cancel("tx_other", 1_000))

        val result = refundReplying(TossFixtures.payment(status = "PARTIAL_CANCELED", balance = 2_000, cancels = cancels, lastTransactionKey = "tx_not_in_the_list"))

        assertEquals(PaymentOperationStatus.UNKNOWN, result.status)
        assertNull(result.providerOperationId)
    }

    @Test
    fun `without a last transaction key nothing can be matched - unknown`() {
        val result = refundReplying(TossFixtures.payment(status = "PARTIAL_CANCELED", balance = 2_000, cancels = listOf(TossFixtures.cancel("tx_a", 1_000))))

        assertEquals(PaymentOperationStatus.UNKNOWN, result.status)
    }

    @Test
    fun `cancel is as strict as refund - DONE is not a cancellation and an unmatched cancel is unknown`() {
        server.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Json(200, TossFixtures.payment(status = "DONE", cancels = null)))
        val done = provider.cancel(dev.sumin.skeleton.payment.PaymentCancelRequest("pay_key_1", idempotencyKey = IdempotencyKey("c_1")))
        assertEquals(PaymentOperationStatus.UNKNOWN, done.status)

        server.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Json(200, TossFixtures.payment(status = "CANCELED", balance = 0, cancels = listOf(TossFixtures.cancel("tx_c", 3_000)), lastTransactionKey = "tx_c")))
        val canceled = provider.cancel(dev.sumin.skeleton.payment.PaymentCancelRequest("pay_key_1", idempotencyKey = IdempotencyKey("c_2")))
        assertEquals(PaymentOperationStatus.CANCELED, canceled.status)
        assertEquals("tx_c", canceled.providerOperationId)
    }

    @Test
    fun `a transaction key that is not an identifier is not matched or reported`() {
        val odd = "tx key <b>"
        val result = refundReplying(TossFixtures.payment(status = "CANCELED", balance = 0, cancels = listOf(TossFixtures.cancel(odd, 3_000)), lastTransactionKey = odd))

        assertEquals(PaymentOperationStatus.UNKNOWN, result.status)
        assertNull(result.providerOperationId)
    }

    @Test
    fun `a confirm answer without the key, the total or the currency is unreadable - the request's values are never filled in`() {
        val bodies = mapOf(
            "only a status" to """{"status":"DONE"}""",
            "no payment key" to TossFixtures.payment(extra = mapOf("paymentKey" to null)),
            "no total" to TossFixtures.payment(extra = mapOf("totalAmount" to null)),
            "no currency" to TossFixtures.payment(extra = mapOf("currency" to null)),
        )
        bodies.forEach { (label, body) ->
            server.on("POST", "/v1/payments/confirm", Reply.Json(200, body))

            val error = assertFailsWith<dev.sumin.skeleton.payment.PaymentProviderException>(label) {
                provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))
            }

            assertEquals("UNREADABLE_RESPONSE", error.providerError.code, label)
            assertEquals(dev.sumin.skeleton.payment.PaymentFailureOutcome.UNKNOWN, dev.sumin.skeleton.payment.PaymentFailures.outcomeOf(error), label)
        }
    }

    @Test
    fun `a confirm result carries the card, the method and the approval time from the answer`() {
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))

        val result = provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))

        assertEquals(dev.sumin.skeleton.payment.PaymentCard("HYUNDAI", "123*"), result.card)
        assertEquals("CARD", result.attributes["method"], "the Korean label is mapped to the neutral kind code")
        assertEquals(java.time.Instant.parse("2026-10-08T01:00:00Z"), result.paidAt)
    }

    @Test
    fun `a confirm answer without a card or an approval time leaves both empty`() {
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment(card = null, approvedAt = null)))

        val result = provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))

        assertNull(result.card)
        assertNull(result.paidAt)
    }

    @Test
    fun `a trace id and a status that are not code-shaped are not passed on`() {
        server.on(
            "POST",
            "/v1/payments/confirm",
            Reply.Json(200, TossFixtures.payment(extra = mapOf("status" to "DONE", "traceId" to "010-1234-5678 hong")), mapOf("X-Toss-Trace-Id" to "has spaces and junk")),
        )

        val result = provider.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))

        assertNull(result.trace.providerRequestId)
        assertEquals("DONE", result.trace.rawStatus)
    }

    @Test
    fun `without any idempotency key a refund goes out with no Idempotency-Key header - sending it again would refund twice`() {
        FakeTossServer(requireIdempotencyKey = false).use { open ->
            open.on("POST", "/v1/payments/pay_key_1/cancel", Reply.Json(200, TossFixtures.payment(status = "CANCELED", balance = 0, cancels = listOf(TossFixtures.cancel("tx_all", 3_000)), lastTransactionKey = "tx_all")))

            tossProvider(open).refund(refundRequest(amount = 3_000, key = null))

            assertNull(open.requests.single().header("idempotency-key"))
        }
    }

    @Test
    fun `a confirm with neither a key nor an order number goes out with no Idempotency-Key header`() {
        FakeTossServer(requireIdempotencyKey = false).use { open ->
            open.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))

            tossProvider(open).confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW")))

            assertNull(open.requests.single().header("idempotency-key"))
        }
    }

    @Test
    fun `the secret key is trimmed before it is used for Basic auth - the same as the guard and the mode`() {
        val padded = TossPaymentProvider(
            httpClient = dev.sumin.skeleton.payment.toss.support.realHttpClient(),
            properties = dev.sumin.skeleton.payment.toss.support.tossProperties(server).copy(secretKey = "  ${server.secretKey}\n"),
        )
        server.on("POST", "/v1/payments/confirm", Reply.Json(200, TossFixtures.payment()))

        padded.confirm(PaymentConfirmRequest("pay_key_1", PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))

        assertEquals(1, server.requests.size)
        assertEquals(dev.sumin.skeleton.payment.PaymentMode.TEST, padded.mode)
    }
}
