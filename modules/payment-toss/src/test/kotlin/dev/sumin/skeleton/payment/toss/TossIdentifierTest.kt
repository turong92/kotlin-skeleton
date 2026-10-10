package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCancelRequest
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentFailureOutcome
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.FakeTossServer.Reply
import dev.sumin.skeleton.payment.toss.support.TossFixtures
import dev.sumin.skeleton.payment.toss.support.tossProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 결제 키 · 주문 번호는 URL 경로(와 본문)에 들어가는 값이다. 모양이 틀린 값은 **결제사를 부르기 전에** 호출자 잘못(400)으로 거절한다 —
 * 확정 거절로 읽히지 않고(돈에 대해 아무것도 말하지 않는다), "응답을 못 읽었다"고 거짓 보고하지도 않는다.
 * 조회는 응답이 물은 결제가 맞는지도 확인한다.
 */
class TossIdentifierTest {
    private val server = FakeTossServer()
    private val provider = tossProvider(server)

    @AfterTest
    fun tearDown() = server.close()

    private val badKeys = listOf(
        "orders/ord_victim123", // 다른 경로로 새는 길
        "a b",
        "a%2Fb",
        "a?b",
        "k".repeat(201),
    )

    private fun assertRejectedBeforeAnyCall(label: String, block: () -> Unit) {
        val error = assertFailsWith<ApplicationException>(label) { block() }
        assertEquals(400, error.status.value(), label)
        assertFalse(error is PaymentProviderException, "$label: not a provider answer")
        assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error), "$label: never read as a definitive refusal")
        assertTrue(server.requests.isEmpty(), "$label: the provider was called: ${server.requests.map { it.path }}")
        listOf("victim", "..").forEach { assertFalse(error.message.orEmpty().contains(it), "$label: the value is not echoed") }
    }

    @Test
    fun `a payment key of the wrong shape never reaches the provider - lookup, refund, cancel and confirm`() {
        badKeys.forEach { key ->
            if (key.isNotBlank()) { // 빈 결제 키는 요청 객체가 이미 거절한다
                assertRejectedBeforeAnyCall("lookup '$key'") { provider.lookup(PaymentLookupQuery(providerPaymentId = key, orderId = null)) }
                assertRejectedBeforeAnyCall("refund '$key'") {
                    provider.refund(PaymentRefundRequest(key, PaymentAmount(1_000, "KRW"), idempotencyKey = IdempotencyKey("rfd_1")))
                }
                assertRejectedBeforeAnyCall("cancel '$key'") { provider.cancel(PaymentCancelRequest(key, idempotencyKey = IdempotencyKey("c_1"))) }
                assertRejectedBeforeAnyCall("confirm '$key'") {
                    provider.confirm(PaymentConfirmRequest(key, PaymentAmount(3_000, "KRW"), merchantReferenceId = "ord_abcdef123456"))
                }
            }
        }
    }

    @Test
    fun `an order id of the wrong shape never reaches the provider`() {
        listOf("ord/../../v1", "ord one", "x".repeat(65)).forEach { orderId ->
            assertRejectedBeforeAnyCall("lookup order '$orderId'") { provider.lookup(PaymentLookupQuery(providerPaymentId = null, orderId = orderId)) }
        }
    }

    @Test
    fun `a lookup answer for another payment or another order is not a readable answer`() {
        server.on("GET", "/v1/payments/pay_asked", Reply.Json(200, TossFixtures.payment(paymentKey = "pay_someone_else")))
        server.on("GET", "/v1/payments/orders/ord_asked", Reply.Json(200, TossFixtures.payment(orderId = "ord_somebody_else")))

        listOf(PaymentLookupQuery("pay_asked", null), PaymentLookupQuery(null, "ord_asked")).forEach { query ->
            val error = assertFailsWith<PaymentProviderException> { provider.lookup(query) }
            assertEquals("UNREADABLE_RESPONSE", error.providerError.code)
            assertEquals(PaymentFailureOutcome.UNKNOWN, PaymentFailures.outcomeOf(error))
        }
    }

    @Test
    fun `a webhook payment key that could change a path is refused`() {
        listOf("orders/ord_victim123", "x/../y").forEach { key ->
            val body = TossFixtures.webhook(paymentKey = key).toByteArray()
            assertFailsWith<PaymentWebhookRejectedException>(key) { provider.parse(WebhookDelivery(emptyMap(), body)) }
        }
        val ok = provider.parse(WebhookDelivery(emptyMap(), TossFixtures.webhook(paymentKey = "pay_OK-1").toByteArray()))
        assertEquals("pay_OK-1", ok.resourceId)
    }

}
