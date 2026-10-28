package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 끝 - 해지 예약은 단계를 보존하고, 끝나면 할인도 끝나며, 첫 주문 전액 환불은 쿠폰을 돌려주고, 환불은 실제로 낸 돈을 기준으로 한다. */
class SubscriptionDiscountEndTest {
    private val coupons = FakeCouponSource().apply { add("half4", DiscountStage(DiscountStageType.PERCENT, 50, null, 4)) }
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons))

    private fun subscribe(country: String? = "KR"): PaymentSubscription {
        val id = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), country, discounts = listOf(DiscountClaim("coupon", "half4"))).subscription.id
        return kit.subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
    }

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))
    private fun discount(id: String) = checkNotNull(kit.store.findSubscriptionDiscount(id))

    private fun renew(id: String) {
        kit.time.set(checkNotNull(sub(id).currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
    }

    @Test
    fun `a cancel reservation keeps the stages, and when the subscription ends the discount ends with it`() {
        val active = subscribe()
        renew(active.id)
        kit.subscriptions.cancel("acc_1", active.id)

        assertEquals(SubscriptionStatus.CANCELING, sub(active.id).status)
        assertEquals(SubscriptionDiscountStatus.ACTIVE, discount(active.id).status)
        assertEquals(2, discount(active.id).periodsUsed)

        renew(active.id)

        assertEquals(SubscriptionStatus.ENDED, sub(active.id).status)
        assertEquals(SubscriptionDiscountStatus.ENDED, discount(active.id).status)
        assertEquals(2, discount(active.id).periodsUsed, "the periods that were really paid stay on record")
        assertEquals(2, kit.gateway.chargeCalls.size, "no renewal after the cancellation")
    }

    @Test
    fun `a full refund of the first payment frees the coupon and ends the discount - a partial refund of a renewal changes neither the count nor the coupon`() {
        val active = subscribe()
        renew(active.id)
        val renewal = kit.store.listSubscriptionOrders(active.id, 1).single()
        val first = kit.store.listSubscriptionOrders(active.id, 2).last()

        kit.refunds.refund("op_1", renewal.id, "OPERATOR", 500, IdempotencyKey("r1"))
        assertEquals(2, discount(active.id).periodsUsed)
        assertEquals("USED", coupons.stateOf("half4"))
        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)

        kit.refunds.refund("op_1", first.id, "OPERATOR", null, IdempotencyKey("r2"), endSubscription = false)

        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(first.id).status)
        assertEquals("AVAILABLE", coupons.stateOf("half4"), "the first payment's coupon went back to the buyer")
        assertEquals(OrderDiscountStatus.RESTORED, kit.store.listOrderDiscounts(listOf(first.id)).single().status)
        assertEquals(2, discount(active.id).periodsUsed, "periods already consumed are not given back")
    }

    @Test
    fun `withdrawing inside seven days refunds what was really paid and gives the coupon back`() {
        val active = subscribe()
        kit.time.advance(Duration.ofDays(1))

        val result = kit.cancellations.cancelNow("acc_1", active.id)

        assertTrue(result.quote.withdrawal)
        assertEquals(1_950, result.quote.refund, "the paid amount, not the list price of 3,900")
        assertEquals(1_950, kit.gateway.refundCalls.sumOf { it.amount.amount })
        assertEquals("AVAILABLE", coupons.stateOf("half4"))
        assertEquals(SubscriptionDiscountStatus.ENDED, discount(active.id).status)
    }

    @Test
    fun `cancelling in the middle of a discounted period refunds the unused share of what was paid, never more`() {
        val active = subscribe()
        kit.time.advance(Duration.ofDays(10))

        val result = kit.cancellations.cancelNow("acc_1", active.id)

        assertTrue(result.quote.refund in 1..1_950, "at most the 1,950 that was paid, was ${result.quote.refund}")
        assertTrue(result.quote.refund < 1_500, "proportional to the paid 1,950 (the list-price share would be ~2,300), was ${result.quote.refund}")
        assertEquals(result.quote.refund, kit.gateway.refundCalls.single().amount.amount)
    }
}
