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

/** 무료(0원) 과금 주문은 선점(`CONFIRMING`)과 `PAID` 가 다른 트랜잭션이다 - 그 사이에 죽어도 다음 걸음이 이어서 끝낸다(안 그러면 영영 무료 이용 / 구독이 자리를 쥔 채 굳는다). */
class SubscriptionFreeChargeRecoveryTest {
    private val coupons = FakeCouponSource().apply {
        add("free1", listOf(DiscountStage(DiscountStageType.FREE, 0, null, 1), DiscountStage(DiscountStageType.PERCENT, 50, null, 2)))
        add("free2", listOf(DiscountStage(DiscountStageType.PERCENT, 50, null, 1), DiscountStage(DiscountStageType.FREE, 0, null, 1)))
    }
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons))
    private val auth = mapOf("authKey" to "auth_1")
    private fun claim(id: String) = listOf(DiscountClaim("coupon", id))
    private fun killFreeSettlement() { kit.tasks.failEnqueueWhen = { it.type == PaymentTaskTypes.ORDER_FULFIL } }
    private fun reviveSystem() { kit.tasks.failEnqueueWhen = null }

    @Test
    fun `the first payment of a free first period that died between the claim and the settlement is finished by the resend`() {
        val id = kit.subscriptions.start("acc_1", "plus", IdempotencyKey("k1"), discounts = claim("free1")).subscription.id
        killFreeSettlement()
        assertFailsWith<IllegalStateException> { kit.subscriptions.activate("acc_1", id, auth) }
        val stuck = checkNotNull(kit.store.findSubscription(id))
        assertEquals(SubscriptionStatus.PENDING, stuck.status)
        assertNotNull(stuck.activeKey, "the subscription holds the account's slot while it is stuck")
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(checkNotNull(stuck.chargeOrderId)).status)
        assertEquals("RESERVED", coupons.stateOf("free1"))

        reviveSystem()
        val finished = kit.subscriptions.activate("acc_1", id, auth)

        assertEquals(SubscriptionStatus.ACTIVE, finished.status)
        assertEquals(PaymentOrderStatus.PAID, kit.order(checkNotNull(finished.chargeOrderId)).status)
        assertEquals("USED", coupons.stateOf("free1"))
        assertEquals(0, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `starting another subscription meets the stuck free first payment, finishes it, and is told the account is already subscribed`() {
        val id = kit.subscriptions.start("acc_1", "plus", IdempotencyKey("k1"), discounts = claim("free1")).subscription.id
        killFreeSettlement()
        assertFailsWith<IllegalStateException> { kit.subscriptions.activate("acc_1", id, auth) }
        reviveSystem()

        assertFailsWith<PaymentAlreadySubscribedException> { kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k2")) }

        assertEquals(SubscriptionStatus.ACTIVE, kit.store.findSubscription(id)!!.status, "the stuck one was finished on the way")
        assertEquals("USED", coupons.stateOf("free1"))
    }

    @Test
    fun `a free renewal that died after the claim is finished by the next run of the renewal task - the period moves and nobody uses the service for free forever`() {
        val id = kit.subscriptions.start("acc_1", "plus", IdempotencyKey("k1"), discounts = claim("free2")).subscription.id
        kit.subscriptions.activate("acc_1", id, auth)
        kit.time.set(checkNotNull(kit.store.findSubscription(id)!!.currentPeriodEnd))   // 두 번째 기간은 무료
        killFreeSettlement()
        assertFailsWith<IllegalStateException> { kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, id, 1, 12)) }
        val stuck = checkNotNull(kit.store.findSubscription(id))
        assertEquals(1, stuck.periodSeq, "the period did not move")
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(checkNotNull(stuck.chargeOrderId)).status)

        reviveSystem()
        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, id, 2, 12))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(2, kit.store.findSubscription(id)!!.periodSeq)
        assertEquals(2, kit.store.listSubscriptionOrders(id, 10).size, "no second order was made for the same period")
        assertEquals(1, kit.gateway.chargeCalls.size, "only the half-price first period went to the provider")
    }
}
