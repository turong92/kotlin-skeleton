package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 결제사가 결제창을 열 때 거래 번호를 **먼저 정하는** 결제창(카카오페이 `ready` 의 `tid`): 장부는 그 번호를 주문에 박고,
 * 돌아온 승인 요청이 다른 번호를 들고 오면 결제사를 부르지 않는다. 승인에 필요한 인증 토큰(`pg_token`)과 계정은 결제사에 그대로 닿는다.
 * 지키는 것: 주문에 박힌 거래 번호를 바꿔치기한 승인은 막힘 · 같은 주문을 다시 열면 새 번호가 이긴다 · 거래 번호가 없는 결제창(토스)은 지금과 같다.
 */
class RedirectCheckoutLedgerTest {
    private val kit = PaymentLedgerKit()

    private fun withTransactionIds() {
        var seq = 0
        kit.gateway.checkoutPaymentId = { "T${++seq}" }
    }

    @Test
    fun `the transaction id the provider fixed at checkout is stored on the order and handed to the buyer's order`() {
        withTransactionIds()

        val created = kit.create(key = "k1")

        assertEquals("T1", created.order.providerPaymentId)
        assertEquals("T1", kit.order(created.order.id).providerPaymentId)
        assertEquals("T1", created.checkout?.providerPaymentId)
    }

    @Test
    fun `opening the same pending order again stores the newer transaction id - the window the buyer sees now is the one that can be approved`() {
        withTransactionIds()
        val first = kit.create(key = "k1")

        val again = kit.create(key = "k1")

        assertEquals(first.order.id, again.order.id)
        assertEquals("T2", again.order.providerPaymentId)
        assertEquals("T2", kit.order(first.order.id).providerPaymentId)
    }

    @Test
    fun `a checkout that fixes no transaction id leaves the order without one, as before`() {
        val created = kit.create(key = "k1")

        assertNull(created.order.providerPaymentId)
        assertNull(created.checkout?.providerPaymentId)
    }

    @Test
    fun `a confirm that brings another transaction id than the one stored never reaches the provider`() {
        withTransactionIds()
        val order = kit.create(key = "k1").order
        kit.gateway.authenticate("T-other", order.id, order.amount)

        assertFailsWith<PaymentOrderNotPayableException> { kit.orders.confirm("acc_1", order.id, "T-other", order.amount, "tok") }

        assertEquals(0, kit.gateway.confirmCalls.size)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }

    @Test
    fun `a confirm with the stored transaction id reaches the provider with the approval token and the account, and pays the order`() {
        withTransactionIds()
        val order = kit.create(key = "k1").order
        kit.gateway.authenticate("T1", order.id, order.amount)

        val paid = kit.orders.confirm("acc_1", order.id, "T1", order.amount, "pg_tok_1")

        assertEquals(PaymentOrderStatus.PAID, paid.status)
        val request = kit.gateway.confirmRequests.single()
        assertEquals("pg_tok_1", request.approvalToken)
        assertEquals("acc_1", request.accountId)
        assertEquals("T1", request.providerPaymentId)
    }

    @Test
    fun `a confirm without a token (the card window) carries no token`() {
        val order = kit.createAuthenticated()

        kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)

        assertNull(kit.gateway.confirmRequests.single().approvalToken)
    }

    @Test
    fun `a malformed or missing approval token is refused with a 400 before the claim - the order stays PENDING and the buyer can still pay`() {
        withTransactionIds()
        val order = kit.create(key = "k1").order
        kit.gateway.authenticate("T1", order.id, order.amount)
        kit.gateway.acceptsApprovalToken = { it == "good" }

        for (token in listOf(null, "forged token")) {
            assertFailsWith<dev.sumin.skeleton.common.FieldValidationException> { kit.orders.confirm("acc_1", order.id, "T1", order.amount, token) }
        }

        assertEquals(0, kit.gateway.confirmCalls.size)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertEquals(PaymentOrderStatus.PAID, kit.orders.confirm("acc_1", order.id, "T1", order.amount, "good").status)
    }

    @Test
    fun `a provider that swaps the currency itself (a transaction-first one like Paddle) is never a choice for the buyer`() {
        val props = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            providers = mapOf(
                "scripted" to PaymentProperties.Provider(countries = setOf("KR")),
                "paddle" to PaymentProperties.Provider(countries = setOf("KR")),
            ),
        )
        val both = PaymentLedgerKit(properties = props)

        assertEquals(listOf("scripted"), both.router.choosableProviders("KR"))
    }

    @Test
    fun `a provider the buyer picked must serve the quote currency - the server's own pick is not checked`() {
        val props = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            providers = mapOf("scripted" to PaymentProperties.Provider(currencies = setOf("USD"), countries = setOf("US"))),
        )
        val strict = PaymentLedgerKit(properties = props)

        assertFailsWith<PaymentRoutingException> {
            strict.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("c1"), provider = "scripted", providerChosen = true))
        }
        assertEquals(0, strict.store.allOrders().size)
        strict.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("c2"), provider = "scripted"))
        assertEquals(1, strict.store.allOrders().size)
        assertEquals(false, strict.router.servesCountry("scripted", "KR"), "the operator's countries override is the effective list")
        assertEquals(true, strict.router.servesCountry("scripted", "us"))
    }
}
