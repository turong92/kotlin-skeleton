package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.tossProvider
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 조회(`PaymentLookupGateway`) — 가짜 토스 서버와 진짜 HTTP 클라이언트로 (스펙 §6.6 대응표 · §4.1). */
class TossLookupTest {
    private val server = FakeTossServer()
    private val lookup: PaymentLookupGateway = tossProvider(server)

    @AfterTest
    fun tearDown() = server.close()

    private fun byKey(key: String = "pay_key_1") = PaymentLookupQuery(providerPaymentId = key, orderId = null)

    private fun byOrder(orderId: String = "ord_abcdef123456") = PaymentLookupQuery(providerPaymentId = null, orderId = orderId)

    @Test
    fun `lookup by payment key reads the payment with Basic auth and no body`() {
        server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment()))

        val snapshot = requireNotNull(lookup.lookup(byKey()))

        val request = server.requests.single()
        assertEquals("/v1/payments/pay_key_1", request.path)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("${server.secretKey}:".toByteArray()), request.header("authorization"))
        assertEquals("", request.body)
        assertEquals("pay_key_1", snapshot.providerPaymentId)
        assertEquals(ProviderPaymentStatus.PAID, snapshot.status)
        assertEquals(PaymentAmount(3_000, "KRW"), snapshot.total)
    }

    @Test
    fun `404 NOT_FOUND_PAYMENT means there is no payment - null, for both lookups - and by order id reads the order path`() {
        server.on("GET", "/v1/payments/pay_gone", TossFixtures.error(404, "NOT_FOUND_PAYMENT"))
        server.on("GET", "/v1/payments/orders/ord_gone", TossFixtures.error(404, "NOT_FOUND_PAYMENT"))
        server.on("GET", "/v1/payments/orders/ord_abcdef123456", Reply.Json(200, TossFixtures.payment()))
        assertEquals("pay_key_1", requireNotNull(lookup.lookup(byOrder())).providerPaymentId)

        assertNull(lookup.lookup(byKey("pay_gone")))
        assertNull(lookup.lookup(byOrder("ord_gone")))
    }

    @Test
    fun `every Toss status maps to one provider status`() {
        val table = mapOf(
            "READY" to ProviderPaymentStatus.IN_PROGRESS,
            "IN_PROGRESS" to ProviderPaymentStatus.IN_PROGRESS,
            "WAITING_FOR_DEPOSIT" to ProviderPaymentStatus.IN_PROGRESS,
            "DONE" to ProviderPaymentStatus.PAID,
            "PARTIAL_CANCELED" to ProviderPaymentStatus.PARTIALLY_REFUNDED,
            "CANCELED" to ProviderPaymentStatus.REFUNDED,
            "ABORTED" to ProviderPaymentStatus.FAILED,
            "EXPIRED" to ProviderPaymentStatus.EXPIRED,
        )
        table.forEach { (tossStatus, expected) ->
            // 환불 상태는 환불 목록이 같이 있어야 읽힌다(상태와 목록이 어긋나면 읽을 수 없는 응답이다)
            val cancels = if (tossStatus.endsWith("CANCELED")) listOf(TossFixtures.cancel("tx_c1", 1_000)) else null
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = tossStatus, cancels = cancels)))
            assertEquals(expected, requireNotNull(lookup.lookup(byKey())).status, tossStatus)
        }
    }

    @Test
    fun `a status this module does not know is an unreadable answer - never a guess`() {
        listOf("SOMETHING_NEW", "done").forEach { status ->
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = status)))
            val error = assertFailsWith<PaymentProviderException>(status) { lookup.lookup(byKey()) }
            assertEquals("UNREADABLE_RESPONSE", error.providerError.code)
            assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error))
        }
    }

    @Test
    fun `total, remaining and only the cancels that are DONE become the refund list`() {
        val cancels = listOf(
            TossFixtures.cancel("tx_c1", 3_000, canceledAt = "2026-10-08T10:15:30+09:00"),
            TossFixtures.cancel("tx_c2", 500, status = "IN_PROGRESS"),
            TossFixtures.cancel("tx_c3", 700, status = "ABORTED"),
            TossFixtures.cancel("tx_c4", 1_000, canceledAt = "2026-10-08T11:00:00+09:00"),
        )
        server.on(
            "GET",
            "/v1/payments/pay_key_1",
            Reply.Json(200, TossFixtures.payment(status = "PARTIAL_CANCELED", total = 10_000, balance = 6_000, cancels = cancels)),
        )

        val snapshot = requireNotNull(lookup.lookup(byKey()))

        assertEquals(PaymentAmount(10_000, "KRW"), snapshot.total)
        assertEquals(PaymentAmount(6_000, "KRW"), snapshot.remaining)
        assertEquals(
            listOf(
                ProviderRefund("tx_c1", PaymentAmount(3_000, "KRW"), Instant.parse("2026-10-08T01:15:30Z")),
                ProviderRefund("tx_c4", PaymentAmount(1_000, "KRW"), Instant.parse("2026-10-08T02:00:00Z")),
            ),
            snapshot.refunds,
        )
    }

    @Test
    fun `a DONE cancel without a transaction key or time, or a refunded status without any DONE cancel, is unreadable - a refund is never invented`() {
        val noKey = TossFixtures.cancel("x", 1_000).toMutableMap().apply { put("transactionKey", null) }
        val noTime = TossFixtures.cancel("tx", 1_000).toMutableMap().apply { put("canceledAt", null) }
        listOf(noKey, noTime).forEach { cancel ->
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = "PARTIAL_CANCELED", cancels = listOf(cancel))))
            val error = assertFailsWith<PaymentProviderException> { lookup.lookup(byKey()) }
            assertEquals("UNREADABLE_RESPONSE", error.providerError.code)
        }
        listOf("CANCELED", "PARTIAL_CANCELED").forEach { status -> // 환불 상태인데 DONE 취소가 없다 = 스스로 모순
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(status = status, balance = 0, cancels = null)))
            assertEquals("UNREADABLE_RESPONSE", assertFailsWith<PaymentProviderException>(status) { lookup.lookup(byKey()) }.providerError.code)
        }
    }


    @Test
    fun `card issuer code is translated and only the last four characters of the number are kept`() {
        fun cardOf(card: Map<String, Any?>?): PaymentCard? {
            server.on("GET", "/v1/payments/pay_key_1", Reply.Json(200, TossFixtures.payment(card = card)))
            return requireNotNull(lookup.lookup(byKey())).card
        }

        assertEquals(PaymentCard("HYUNDAI", "123*"), cardOf(TossFixtures.card(issuerCode = "61", number = "43301234****123*")))
        assertEquals(PaymentCard("SHINHAN", "1234"), cardOf(TossFixtures.card(issuerCode = "41", number = "4330123412341234")))
        assertEquals(PaymentCard(null, "123*"), cardOf(TossFixtures.card(issuerCode = "<script>alert(1)</script>", number = "43301234****123*")), "an issuer code that is not a plain code is dropped")
        assertEquals(PaymentCard("HYUNDAI", null), cardOf(TossFixtures.card(issuerCode = "61", number = "4330-1234-12ab")), "a last four with letters is dropped")
        assertNull(cardOf(null), "not a card payment")
    }

    @Test
    fun `a lookup error says nothing about whether money moved - 401, 403 and 400 are unknown, never definitive`() {
        listOf(401 to "UNAUTHORIZED_KEY", 403 to "FORBIDDEN_REQUEST", 400 to "INVALID_REQUEST", 404 to "NOT_FOUND_PAYMENT_SESSION", 403 to "REJECT_CARD_PAYMENT").forEach { (status, code) ->
            server.on("GET", "/v1/payments/pay_key_1", TossFixtures.error(status, code))

            val error = assertFailsWith<PaymentProviderException>("$status $code") { lookup.lookup(byKey()) }

            assertEquals(code, error.providerError.code)
            assertFalse(error.providerError.definitive, "$status $code")
            assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error), "$status $code")
        }
    }

    private fun tossPropertiesWithKey(server: FakeTossServer, key: String) =
        TossPaymentProperties(enabled = true, secretKey = key, baseUrl = server.baseUrl)
}
