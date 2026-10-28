package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 월 → 연(더 긴 주기) 갈아타기에서 깨지면 사고 나는 것: 새 가격 - **실제로 낸 돈**의 남은 몫을 결제하는가 · 결제가 확정돼야 주기가 바뀌는가(거절이면 그대로) · 오늘부터 새 주기이고 다음 갱신이 새 주기 끝에 나가는가.
 * 메모리 저장소 + 빌링 대역.
 */
class PlanChangeNewPeriodTest {
    private fun newKit() = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, Charge.PRORATED_DIFFERENCE, 0)),
        ),
    ).also {
        it.plans.plans += SubscriptionPlan("basic-yearly", "Basic yearly", PaymentAmount(39_000, "KRW"), "P1Y", 1, productCode = "basic")
        it.plans.plans += SubscriptionPlan("plus-yearly", "Plus yearly", PaymentAmount(69_000, "KRW"), "P1Y", 2, productCode = "plus")
    }

    private fun PaymentLedgerKit.active(): PaymentSubscription {
        val id = subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        return subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
    }

    private fun PaymentLedgerKit.sub(id: String) = checkNotNull(store.findSubscription(id))

    private fun creditOf(sub: PaymentSubscription, net: Long, now: java.time.Instant) =
        Proration.remaining(net, checkNotNull(sub.currentPeriodStart), checkNotNull(sub.currentPeriodEnd), now)

    @Test
    fun `the charge is the new price minus the remaining share of what was paid, and the period restarts today at the new length`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(15))
        val credit = creditOf(active, 3_900, kit.time.now())
        val quote = kit.planChanges.preview("acc_1", active.id, "basic-yearly")

        assertEquals(PlanChangeKind.IMMEDIATE_NEW_PERIOD, quote.kind)
        assertEquals(39_000 - credit, quote.charge.amount)
        assertEquals(true, credit in 1_800..2_100, "about half of 3,900, was $credit")

        val changed = kit.planChanges.change("acc_1", active.id, "basic-yearly", quote.charge.amount, IdempotencyKey("chg-1"))

        assertEquals(quote.charge.amount, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(listOf("basic-yearly", "P1Y", 39_000L, 1), listOf(changed.planCode, changed.period, changed.amount.amount, changed.periodSeq))
        val paidAt = kit.store.listSubscriptionOrders(active.id, 1).single().paidAt
        assertEquals(paidAt, changed.anchorAt, "the new period starts the day it was paid")
        assertEquals(SubscriptionPeriod.parse("P1Y").after(checkNotNull(paidAt), 1), changed.currentPeriodEnd)
        assertNull(changed.pendingPeriod)

        kit.time.set(checkNotNull(changed.currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        assertEquals(39_000, kit.gateway.chargeCalls.last().amount.amount, "the next renewal is the yearly price at the end of the new period")
    }

    @Test
    fun `a declined charge leaves plan and period as they were, and the monthly renewal still charges the old price`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(15))
        kit.gateway.script(ConfirmScript.Decline())

        assertFailsWith<PaymentDeclinedException> { kit.planChanges.change("acc_1", active.id, "basic-yearly", 99_999, IdempotencyKey("chg-1")) }

        val after = kit.sub(active.id)
        assertEquals(listOf("basic", "P1M", null), listOf(after.planCode, after.period, after.pendingPeriod))
        kit.time.set(checkNotNull(after.currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        assertEquals(3_900, kit.gateway.chargeCalls.last().amount.amount)
    }

    @Test
    fun `the credit is what was really paid - a half-price month gives half the credit`() {
        val kit = newKit()
        val active = kit.active()
        val order = kit.store.listSubscriptionOrders(active.id, 1).single()
        kit.store.inTransaction { tx -> tx.lockOrder(order.id); tx.updateOrder(order.copy(paidAmount = 1_950)) }   // 쿠폰으로 반값을 냈다
        kit.time.advance(Duration.ofDays(15))

        val quote = kit.planChanges.preview("acc_1", active.id, "basic-yearly")

        assertEquals(39_000 - creditOf(active, 1_950, kit.time.now()), quote.charge.amount)
    }

    @Test
    fun `a higher tier with a longer period is one step - the credit is taken off the yearly price of the higher tier`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(15))
        val quote = kit.planChanges.preview("acc_1", active.id, "plus-yearly")
        assertEquals(69_000 - creditOf(active, 3_900, kit.time.now()), quote.charge.amount)

        val changed = kit.planChanges.change("acc_1", active.id, "plus-yearly", quote.charge.amount, IdempotencyKey("chg-1"))

        assertEquals(listOf("plus-yearly", "P1Y"), listOf(changed.planCode, changed.period))
    }

    @Test
    fun `a change that is still unanswered at the old period end does not renew, and when it settles the period is the new one`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(15))
        kit.gateway.script(ConfirmScript.TimeoutApplied())

        val pending = kit.planChanges.change("acc_1", active.id, "basic-yearly", 99_999, IdempotencyKey("chg-1"))
        assertEquals("basic-yearly", pending.pendingPlanCode)
        assertEquals("P1Y", pending.pendingPeriod)
        assertEquals("P1M", pending.period)

        kit.advance(kit.properties.reconcile.firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        val after = kit.sub(active.id)
        assertEquals(listOf("basic-yearly", "P1Y", 1), listOf(after.planCode, after.period, after.periodSeq))
        assertNull(after.pendingPeriod)
    }
}
