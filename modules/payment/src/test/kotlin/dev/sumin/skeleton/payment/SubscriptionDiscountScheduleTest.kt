package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 구독 할인 일정에서 깨지면 돈 사고가 나는 것: 회차는 PAID 때만 · 실패한 시도는 회차를 안 쓴다 · 0원 기간은 결제사를 안 부른다 · 영원한 할인. */
class SubscriptionDiscountScheduleTest {
    private val coupons = FakeCouponSource()
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons)).also {
        it.plans.plans += SubscriptionPlan("pro", "Pro", PaymentAmount(9_900, "KRW"), "P1M", 3)
    }
    private val auth = mapOf("authKey" to "auth_1")
    private fun claim(id: String) = listOf(DiscountClaim("coupon", id))
    private fun stage(type: DiscountStageType, value: Long = 0, periods: Int? = null) = DiscountStage(type, value, null, periods)

    private fun subscribe(couponId: String, plan: String = "pro"): PaymentSubscription {
        val id = kit.subscriptions.start("acc_1", plan, IdempotencyKey("k1"), discounts = claim(couponId)).subscription.id
        return kit.subscriptions.activate("acc_1", id, auth)
    }

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    private fun discount(id: String) = checkNotNull(kit.store.findSubscriptionDiscount(id))

    /** 한 기간을 넘겨 갱신 과금 · 지급까지 돌린다. */
    private fun renew(id: String) {
        kit.time.set(checkNotNull(sub(id).currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
    }

    private fun chargedAmounts(id: String) = kit.store.listSubscriptionOrders(id, 20).reversed().map { it.amount.amount }

    @Test
    fun `one free period then 50 percent for three - the payments are 0, 4950, 4950, 4950 and then the list price - and the free period never calls the provider`() {
        coupons.add("c", listOf(stage(DiscountStageType.FREE, periods = 1), stage(DiscountStageType.PERCENT, 50, periods = 3)))
        val active = subscribe("c")

        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals(0, kit.gateway.chargeCalls.size, "the first period is free - the card is registered, nothing is charged")
        assertEquals(1, kit.gateway.issueCalls.size)
        assertEquals(1, discount(active.id).periodsUsed)
        repeat(4) { renew(active.id) }

        assertEquals(listOf(0L, 4_950L, 4_950L, 4_950L, 9_900L), chargedAmounts(active.id))
        assertEquals(listOf(4_950L, 4_950L, 4_950L, 9_900L), kit.gateway.chargeCalls.map { it.amount.amount })
        assertEquals(SubscriptionDiscountStatus.ENDED, discount(active.id).status)
        assertEquals(4, discount(active.id).periodsUsed)
        assertEquals(5, sub(active.id).periodSeq)
        // 마지막 결제는 정가 - 할인 칸이 없다
        val last = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertNull(last.originalAmount)
        assertEquals(0, last.discountAmount)
    }

    @Test
    fun `a discounted order carries the list price and the discount, and the subscription shows the next amount and the remaining periods`() {
        coupons.add("c", listOf(stage(DiscountStageType.PERCENT, 50, periods = 3)))
        val active = subscribe("c")
        val first = kit.store.listSubscriptionOrders(active.id, 1).single()

        assertEquals(4_950, first.amount.amount)
        assertEquals(9_900, first.originalAmount)
        assertEquals(4_950, first.discountAmount)
        val view = checkNotNull(kit.subscriptions.discountOf(sub(active.id)))
        assertEquals(4_950, view.nextAmount.amount)
        assertEquals(2, view.remainingPeriods)
        assertEquals(1, view.periodsUsed)
    }

    @Test
    fun `a free period in the middle moves the period without calling the provider, even when the billing key is gone`() {
        coupons.add("c", listOf(stage(DiscountStageType.PERCENT, 50, periods = 1), stage(DiscountStageType.FREE, periods = 2)))
        val active = subscribe("c")
        assertEquals(1, kit.gateway.chargeCalls.size.coerceAtLeast(0) - 0 + 0, "first payment of 4,950 went to the provider once")
        kit.store.inTransaction { tx -> tx.lockSubscription(active.id)!!.let { tx.updateSubscription(it.copy(billingToken = null)) } }

        renew(active.id)

        assertEquals(2, sub(active.id).periodSeq)
        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
        assertEquals(1, kit.gateway.chargeCalls.size, "the free renewal did not touch the provider")
        val free = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(PaymentOrders.FREE_PROVIDER, free.provider)
        assertEquals(0, free.amount.amount)
        assertEquals(PaymentOrderStatus.PAID, free.status)
        assertEquals(2, discount(active.id).periodsUsed)
    }

    @Test
    fun `a declined renewal keeps the period count, and the retry charges the very same amount`() {
        coupons.add("c", listOf(stage(DiscountStageType.PERCENT, 50, periods = 3)))
        val active = subscribe("c")
        kit.gateway.script(ConfirmScript.Decline("REJECT_CARD_COMPANY"))

        renew(active.id)

        assertEquals(SubscriptionStatus.PAST_DUE, sub(active.id).status)
        assertEquals(1, discount(active.id).periodsUsed, "a failed attempt does not use a discounted period")
        assertEquals(4_950, kit.gateway.chargeCalls.last().amount.amount)

        kit.advance(kit.properties.subscription.renewal.retryInterval)
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(listOf(4_950L, 4_950L, 4_950L), kit.gateway.chargeCalls.map { it.amount.amount })
        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
        assertEquals(2, discount(active.id).periodsUsed)
        assertEquals(2, sub(active.id).periodSeq)
    }

    @Test
    fun `a percent off forever is still applied on the tenth renewal and never ends`() {
        coupons.add("forever", listOf(stage(DiscountStageType.PERCENT, 10)))
        val active = subscribe("forever")

        repeat(10) { renew(active.id) }

        assertEquals(List(11) { 8_910L }, chargedAmounts(active.id))
        assertEquals(SubscriptionDiscountStatus.ACTIVE, discount(active.id).status)
        val view = checkNotNull(kit.subscriptions.discountOf(sub(active.id)))
        assertNull(view.remainingPeriods)
        assertEquals(8_910, view.nextAmount.amount)
    }

    @Test
    fun `a subscription without a discount charges the list price and has no discount row`() {
        val plain = PaymentLedgerKit(discountSources = listOf(coupons))
        val id = plain.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        plain.subscriptions.activate("acc_1", id, auth)

        assertNull(plain.store.findSubscriptionDiscount(id))
        assertNull(plain.subscriptions.discountOf(checkNotNull(plain.store.findSubscription(id))))
        assertEquals(3_900, plain.gateway.chargeCalls.single().amount.amount)
    }
}
