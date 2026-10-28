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

    private fun PaymentLedgerKit.activeIn(country: String = "KR"): PaymentSubscription {
        val id = subscriptions.start("acc_1", "basic", IdempotencyKey("k-kr"), country).subscription.id
        return subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
    }

    @Test
    fun `cancelling after month to year refunds on the whole new price, not on the change payment alone - the Korean policy sample`() {
        val kit = newKit()
        val active = kit.activeIn()
        kit.time.advance(Duration.ofDays(15))
        val changed = kit.planChanges.change("acc_1", active.id, "basic-yearly", 99_999, IdempotencyKey("chg-1"))
        kit.time.advance(Duration.ofDays(8))   // 철회 기간(7일)을 지났다

        val preview = kit.cancellations.preview("acc_1", changed.id).quote

        assertEquals(31_590, preview.refund, "(39,000 - one month at 3,900) x 90%")
        val result = kit.cancellations.cancelNow("acc_1", changed.id)
        assertEquals(31_590, result.refunds.sumOf { it.amount })
    }

    @Test
    fun `inside the withdrawal window the whole new price comes back - from the change payment and the carried part of the monthly payment`() {
        val kit = newKit()
        val active = kit.activeIn()
        kit.time.advance(Duration.ofDays(15))
        val changed = kit.planChanges.change("acc_1", active.id, "basic-yearly", 99_999, IdempotencyKey("chg-1"))
        kit.time.advance(Duration.ofDays(3))

        val result = kit.cancellations.cancelNow("acc_1", changed.id)

        assertEquals(39_000, result.quote.refund)
        val orders = kit.store.listSubscriptionOrders(active.id, 5)
        assertEquals(2, result.refunds.size, "the change payment and the monthly payment it took the credit from")
        assertEquals(setOf(orders[0].id, orders[1].id), result.refunds.map { it.orderId }.toSet())
        assertEquals(39_000, result.refunds.sumOf { it.amount })
        assertEquals(true, result.refunds.all { it.status == CancelRefundStatus.SUCCEEDED })
    }

    @Test
    fun `a credit that covers the whole new price makes a free change order, and a cancel still gets the credited money back from the monthly payment`() {
        val kit = newKit()
        kit.plans.plans += SubscriptionPlan("cheap-yearly", "Cheap yearly", PaymentAmount(1_500, "KRW"), "P1Y", 1, productCode = "basic")
        val active = kit.activeIn()
        kit.time.advance(Duration.ofDays(15))   // 공제 약 2,000 > 새 가격 1,500

        val changed = kit.planChanges.change("acc_1", active.id, "cheap-yearly", 0, IdempotencyKey("chg-1"))

        assertEquals(listOf("cheap-yearly", "P1Y"), listOf(changed.planCode, changed.period))
        val change = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(listOf(PaymentOrderKind.SUBSCRIPTION_CHANGE, PaymentOrderStatus.PAID, 0L, 1_500L), listOf(change.kind, change.status, change.amount.amount, change.originalAmount))
        assertEquals(changed.currentPeriodStart, change.paidAt)
        assertEquals(1_500, kit.cancellations.preview("acc_1", changed.id).quote.refund, "inside the withdrawal window the credited 1,500 comes back")
        val result = kit.cancellations.cancelNow("acc_1", changed.id)
        assertEquals(listOf(1_500L), result.refunds.map { it.amount })
        assertEquals(kit.store.listSubscriptionOrders(active.id, 5).last().id, result.refunds.single().orderId, "from the monthly payment")
    }

    @Test
    fun `refunding the change payment in full ends the subscription like any payment that bought the period`() {
        val kit = newKit()
        val active = kit.activeIn()
        kit.time.advance(Duration.ofDays(15))
        val changed = kit.planChanges.change("acc_1", active.id, "basic-yearly", 99_999, IdempotencyKey("chg-1"))
        val changeOrder = kit.store.listSubscriptionOrders(active.id, 1).single()

        kit.refunds.refund("op_1", changeOrder.id, "OPERATOR", null, IdempotencyKey("r-1"))

        val after = checkNotNull(kit.store.findSubscription(changed.id))
        assertEquals(listOf(SubscriptionStatus.ENDED, SubscriptionEndReason.REFUNDED), listOf(after.status, after.endReason))
    }

    @Test
    fun `the preview tells the credit it took off`() {
        val kit = newKit()
        val active = kit.activeIn()
        kit.time.advance(Duration.ofDays(15))

        val quote = kit.planChanges.preview("acc_1", active.id, "basic-yearly")

        assertEquals(39_000 - quote.charge.amount, quote.credit?.amount)
        assertNull(kit.planChanges.preview("acc_1", active.id, "plus").credit)
    }

    @Test
    fun `a month to year change with nothing to credit still ends the subscription when its payment is refunded in full`() {
        val kit = newKit()
        val active = kit.activeIn()
        // 낸 돈이 없는 기간(첫 달 무료 쿠폰 같은 것)을 만든다 - 공제가 0
        val order = kit.store.listSubscriptionOrders(active.id, 1).single()
        kit.store.inTransaction { tx -> tx.lockOrder(order.id); tx.updateOrder(order.copy(paidAmount = 0)) }
        kit.time.advance(Duration.ofDays(15))
        val quote = kit.planChanges.preview("acc_1", active.id, "basic-yearly")
        assertNull(quote.credit)

        val changed = kit.planChanges.change("acc_1", active.id, "basic-yearly", quote.charge.amount, IdempotencyKey("chg-1"))
        val changeOrder = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertNull(changeOrder.originalAmount, "no credit was carried")
        kit.refunds.refund("op_1", changeOrder.id, "OPERATOR", null, IdempotencyKey("r-1"))

        val after = checkNotNull(kit.store.findSubscription(changed.id))
        assertEquals(listOf(SubscriptionStatus.ENDED, SubscriptionEndReason.REFUNDED), listOf(after.status, after.endReason))
    }

    @Test
    fun `a full-restart change in the same period opens a period too, and a difference-only one does not`() {
        val restart = PaymentLedgerKit(
            properties = PaymentProperties(
                webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
                subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, Charge.FULL_RESTART, 0)),
            ),
        )
        val active = restart.active()
        restart.time.advance(Duration.ofDays(5))
        val changed = restart.planChanges.change("acc_1", active.id, "plus", 99_999, IdempotencyKey("chg-1"))
        val order = restart.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(true, CurrentPeriodPayments.opensPeriod(changed, order))

        val diff = newKit()
        val active2 = diff.active()
        diff.time.advance(Duration.ofDays(5))
        val changed2 = diff.planChanges.change("acc_1", active2.id, "plus", 99_999, IdempotencyKey("chg-1"))
        assertEquals(false, CurrentPeriodPayments.opensPeriod(changed2, diff.store.listSubscriptionOrders(active2.id, 1).single()))
    }

    @Test
    fun `a free change order that was claimed but never settled is finished by the renewal task that was woken for it`() {
        val kit = newKit()
        kit.plans.plans += SubscriptionPlan("cheap-yearly", "Cheap yearly", PaymentAmount(1_500, "KRW"), "P1Y", 1, productCode = "basic")
        val active = kit.activeIn()
        kit.time.advance(Duration.ofDays(15))
        // change() 가 하는 선점까지만 재현한다: 0원 주문을 넣고 구독에 대기 값을 건다 - settleFree 전에 죽은 상태
        val claimedOrder = kit.store.inTransaction { tx ->
            val locked = checkNotNull(tx.lockSubscription(active.id))
            val order = kit.lifecycle.chargeOrder(
                locked, PaymentOrderKind.SUBSCRIPTION_CHANGE, "Cheap yearly", amount = PaymentAmount(0, "KRW"), itemRef = "cheap-yearly",
                dedupeKey = "subchg:crash", originalAmount = 1_500, offProvider = true,
            )
            tx.insertOrderIfAbsent(order)
            val claimed = locked.copy(chargeOrderId = order.id, pendingPlanCode = "cheap-yearly", pendingAmount = 1_500, pendingPeriod = "P1Y")
            tx.updateSubscription(claimed)
            kit.lifecycle.scheduleRenewal(claimed, runAt = kit.time.now())   // change() 가 같은 트랜잭션에서 거는 깨우기
            order
        }
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(claimedOrder.id).status)

        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        val after = checkNotNull(kit.store.findSubscription(active.id))
        assertEquals(listOf("cheap-yearly", "P1Y", PaymentOrderStatus.PAID), listOf(after.planCode, after.period, kit.order(claimedOrder.id).status))
    }
}
