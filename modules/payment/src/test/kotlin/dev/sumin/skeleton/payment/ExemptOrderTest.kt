package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `payment.exempt` 를 가진 계정의 주문 - 결제사 없이 완료, 상품은 나가고, 매출 · 환불 · 「결제한 적」에는 들어가지 않는다. */
class ExemptOrderTest {
    private val coupons = FakeCouponSource().apply { add("c30", DiscountStage(DiscountStageType.PERCENT, 30)) }
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons)).also { it.exemptAccounts += "acc_free" }

    @Test
    fun `an exempt caller never meets a provider - provider exempt, original is the catalog price, amount 0, EXEMPTED then PAID, one delivery`() {
        val created = kit.create(accountId = "acc_free")
        val order = created.order

        assertNull(created.checkout)
        assertEquals(PaymentOrders.EXEMPT_PROVIDER, order.provider)
        assertEquals(PaymentSettlement.EXEMPT, order.settlement)
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals(3000, order.originalAmount)
        assertEquals(0, order.amount.amount)
        assertEquals(0, order.discountAmount)
        assertEquals(emptyList(), kit.store.listOrderDiscounts(listOf(order.id)))
        assertEquals(0, kit.gateway.checkoutCalls.size + kit.gateway.confirmCalls.size + kit.gateway.lookupCalls.size)
        val events = kit.store.listEvents(order.id)
        val exempted = events.single { it.type == PaymentEventType.EXEMPTED }
        assertEquals("acc_free", exempted.actorId)
        assertEquals(PaymentActor.USER, exempted.actor)
        assertTrue(events.indexOf(exempted) < events.indexOfFirst { it.toStatus == "PAID" })
        assertEquals(1, kit.listener.paid.size)
        assertNotNull(kit.order(order.id).fulfilledAt)
    }

    @Test
    fun `a coupon sent along is never reserved - exemption wins`() {
        val created = kit.create(accountId = "acc_free", claims = listOf(DiscountClaim("coupon", "c30")))

        assertEquals(PaymentSettlement.EXEMPT, created.order.settlement)
        assertEquals(0, coupons.calls.size)
        assertEquals("AVAILABLE", coupons.stateOf("c30"))
    }

    @Test
    fun `nobody can refund an exempt order - customer request and operator refund both say NOTHING_TO_REFUND`() {
        val order = kit.create(accountId = "acc_free").order

        assertFailsWith<PaymentNothingToRefundException> { kit.refunds.request("acc_free", order.id, "DUPLICATE", null, IdempotencyKey("a")) }
        assertFailsWith<PaymentNothingToRefundException> { kit.refunds.refund("op_1", order.id, "OPERATOR", null, IdempotencyKey("b")) }
        assertEquals(0, kit.gateway.refundCalls.size)
    }

    @Test
    fun `after the permission is taken away the next order opens the checkout`() {
        kit.create(accountId = "acc_free", key = "k1")
        kit.exemptAccounts -= "acc_free"

        val next = kit.create(accountId = "acc_free", key = "k2")

        assertNotNull(next.checkout)
        assertEquals(PaymentSettlement.PROVIDER, next.order.settlement)
        assertEquals(PaymentOrderStatus.PENDING, next.order.status)
    }

    @Test
    fun `an exempt order is not a purchase for the first-purchase-only check`() {
        kit.create(accountId = "acc_free", key = "k1")
        assertFalse(kit.store.hasPaidOrder("acc_free"))

        val normal = kit.paid(accountId = "acc_free2", key = "n1", paymentKey = "pay_n")
        assertNotNull(normal.paidAt)
        assertTrue(kit.store.hasPaidOrder("acc_free2"))
    }

    @Test
    fun `a quote for an exempt caller says EXEMPT with amount 0`() {
        val quote = kit.orders.quote(QuoteCommand("acc_free", "cheer", "cheer-3000", exempt = true))

        assertEquals(PaymentSettlement.EXEMPT, quote.settlement)
        assertEquals(3000, quote.originalAmount)
        assertEquals(0, quote.amount)
        assertEquals(emptyList(), quote.discounts)
    }

    @Test
    fun `the permission is declared for the role screen`() {
        assertTrue(PaymentPermissions.declaration().permissions.any { it.key == "payment.exempt" && it.defaultRoles.isEmpty() })
    }
}
