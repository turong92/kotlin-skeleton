package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 쿠폰으로 최종가가 0원이면 결제사 없이 `free` 로 끝난다. */
class FreeOrderTest {
    private val coupons = FakeCouponSource().apply { add("full", DiscountStage(DiscountStageType.PERCENT, 100)) }
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons))
    private val claims = listOf(DiscountClaim("coupon", "full"))

    @Test
    fun `a 100 percent coupon never calls the provider - provider free, PENDING to PAID in the history, no expiry task, one delivery`() {
        val created = kit.create(claims = claims)
        val order = created.order

        assertNull(created.checkout)
        assertEquals(PaymentOrders.FREE_PROVIDER, order.provider)
        assertEquals(PaymentSettlement.FREE, order.settlement)
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals(0, order.amount.amount)
        assertEquals(3000, order.originalAmount)
        assertEquals(3000, order.discountAmount)
        assertEquals(0, order.paidAmount)
        assertNotNull(order.paidAt)
        assertEquals(0, kit.gateway.checkoutCalls.size + kit.gateway.confirmCalls.size + kit.gateway.lookupCalls.size)
        assertEquals(0, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).size)
        assertEquals(listOf("PENDING" to "PAID"), kit.store.listEvents(order.id).filter { it.fromStatus == "PENDING" }.map { it.fromStatus to it.toStatus })
        assertEquals(1, kit.listener.paid.size)
        assertNotNull(kit.order(order.id).fulfilledAt)
        assertEquals("USED", coupons.stateOf("full"))
        assertEquals(OrderDiscountStatus.CONFIRMED, created.discounts.let { kit.store.listOrderDiscounts(listOf(order.id)).single().status })
    }

    @Test
    fun `the preview of a free purchase says FREE`() {
        val quote = kit.orders.quote(QuoteCommand("acc_1", "cheer", "cheer-3000", discounts = claims))
        assertEquals(PaymentSettlement.FREE, quote.settlement)
        assertEquals(0, quote.amount)
        assertEquals(3000, quote.originalAmount)
    }

    @Test
    fun `a free order cannot be refunded, by the customer or by an operator`() {
        val order = kit.create(claims = claims).order

        assertFailsWith<PaymentNothingToRefundException> { kit.refunds.request("acc_1", order.id, "DUPLICATE", null, IdempotencyKey("a")) }
        assertFailsWith<PaymentNothingToRefundException> { kit.refunds.refund("op_1", order.id, "OPERATOR", null, IdempotencyKey("b")) }
        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(0, kit.store.listRefunds(order.id).size)
    }

    @Test
    fun `the same key again returns the free order and reserves nothing more`() {
        val first = kit.create(key = "same", claims = claims)
        val again = kit.create(key = "same", claims = claims)

        assertEquals(first.order.id, again.order.id)
        assertNull(again.checkout)
        assertEquals(1, coupons.calls.count { it.startsWith("reserve") })
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `if the delivery fails once the order is still paid and the fulfil task delivers once`() {
        kit.listener.failPaid(1)
        val order = kit.create(claims = claims).order

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertNull(kit.order(order.id).fulfilledAt)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        assertEquals(1, kit.listener.paid.size)
    }
}
