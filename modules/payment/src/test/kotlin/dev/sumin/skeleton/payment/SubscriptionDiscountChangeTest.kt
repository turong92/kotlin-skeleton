package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 플랜 변경 - 남은 단계는 새 플랜 금액에 이어서, 변경 차액 결제에는 할인도 회차도 없다. */
class SubscriptionDiscountChangeTest {
    private val coupons = FakeCouponSource().apply {
        add("half4", DiscountStage(DiscountStageType.PERCENT, 50, null, 4))
        add("fixed3000", DiscountStage(DiscountStageType.FIXED, 3_000, null, 4))
    }

    private fun newKit(charge: Charge) = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, charge, 0)),
        ),
        discountSources = listOf(coupons),
    )

    private fun PaymentLedgerKit.subscribe(coupon: String): PaymentSubscription {
        val id = subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), discounts = listOf(DiscountClaim("coupon", coupon))).subscription.id
        return subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
    }

    private fun PaymentLedgerKit.sub(id: String) = checkNotNull(store.findSubscription(id))
    private fun PaymentLedgerKit.used(id: String) = checkNotNull(store.findSubscriptionDiscount(id)).periodsUsed

    private fun PaymentLedgerKit.renew(id: String) {
        time.set(checkNotNull(sub(id).currentPeriodEnd))
        runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
    }

    private fun PaymentLedgerKit.upgrade(id: String): PaymentOrder {
        val quote = planChanges.preview("acc_1", id, "plus")
        planChanges.change("acc_1", id, "plus", quote.charge.amount, IdempotencyKey("chg-1"))
        runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        return store.listSubscriptionOrders(id, 1).single()
    }

    @Test
    fun `after two discounted periods an upgrade charges the plain difference with no discount and no period used, and the next renewal is the new price times the remaining stage`() {
        val kit = newKit(Charge.PRORATED_DIFFERENCE)
        val active = kit.subscribe("half4")
        kit.renew(active.id)
        assertEquals(2, kit.used(active.id))
        kit.time.advance(Duration.ofDays(10))

        val change = kit.upgrade(active.id)

        assertEquals(PaymentOrderKind.SUBSCRIPTION_CHANGE, change.kind)
        assertNull(change.originalAmount, "the proration is not discounted a second time")
        assertEquals(0, change.discountAmount)
        assertEquals(2, kit.used(active.id), "the difference does not use a period")
        assertEquals("plus", kit.sub(active.id).planCode)

        kit.renew(active.id)

        assertEquals(3_450, kit.store.listSubscriptionOrders(active.id, 1).single().amount.amount, "50% of the new 6,900")
        assertEquals(3, kit.used(active.id))
        kit.renew(active.id)
        assertEquals(SubscriptionDiscountStatus.ENDED, checkNotNull(kit.store.findSubscriptionDiscount(active.id)).status)
        kit.renew(active.id)
        assertEquals(6_900, kit.store.listSubscriptionOrders(active.id, 1).single().amount.amount)
    }

    @Test
    fun `with a full restart the period number starts again but the discounted periods used carry on`() {
        val kit = newKit(Charge.FULL_RESTART)
        val active = kit.subscribe("half4")
        kit.renew(active.id)
        kit.time.advance(Duration.ofDays(3))

        kit.upgrade(active.id)

        assertEquals(1, kit.sub(active.id).periodSeq, "the new cycle starts at 1")
        assertEquals(2, kit.used(active.id), "the discount does not start over, and does not skip")
        kit.renew(active.id)
        assertEquals(3_450, kit.store.listSubscriptionOrders(active.id, 1).single().amount.amount)
        assertEquals(3, kit.used(active.id))
    }

    @Test
    fun `a fixed amount off follows the plan - the new price minus the same amount`() {
        val kit = newKit(Charge.PRORATED_DIFFERENCE)
        val active = kit.subscribe("fixed3000")
        assertEquals(900, kit.store.listSubscriptionOrders(active.id, 1).single().amount.amount)
        kit.time.advance(Duration.ofDays(5))

        kit.upgrade(active.id)
        kit.renew(active.id)

        assertEquals(3_900, kit.store.listSubscriptionOrders(active.id, 1).single().amount.amount)
    }
}
