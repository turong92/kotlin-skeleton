package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 플랜 변경에서 깨지면 사고 나는 것: 차액 계산 · 아래 등급 · 거절 시 플랜 불변 · 변경과 갱신의 이중 과금. 메모리 저장소 + 빌링 대역. */
class SubscriptionPlanChangeTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-01-31T00:00:00Z") // 30일 = 2,592,000초

    private fun prorated(now: Instant, minCharge: Long = 0, from: Long = 3_900, to: Long = 6_900) =
        PlanChangePricing.charge(Charge.PRORATED_DIFFERENCE, PaymentAmount(from, "KRW"), PaymentAmount(to, "KRW"), start, end, now, minCharge)

    @Test
    fun `the prorated difference is the price gap times the remaining share of the period, rounded down`() {
        assertEquals(3_000, prorated(start), "at the very start the whole gap")
        assertEquals(2_999, prorated(start.plusSeconds(1)), "one second in is already less - rounded down, never up")
        assertEquals(1_500, prorated(start.plus(Duration.ofDays(15))))
        assertEquals(0, prorated(end.minusSeconds(1)), "the last second of 3,000 won rounds to nothing")
        assertEquals(0, prorated(end.plusSeconds(10)), "after the end nothing is left")
        assertEquals(0, prorated(start, minCharge = 3_001), "a gap below the minimum charge is changed for free")
        assertEquals(3_000, prorated(start, minCharge = 3_000))
        assertEquals(6_900, PlanChangePricing.charge(Charge.FULL_RESTART, PaymentAmount(3_900, "KRW"), PaymentAmount(6_900, "KRW"), start, end, end.minusSeconds(1), 0))
    }

    private fun newKit(charge: Charge = Charge.PRORATED_DIFFERENCE, minCharge: Long = 0) = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, charge, minCharge)),
        ),
    )

    private fun PaymentLedgerKit.active(): PaymentSubscription {
        val id = subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        return subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
    }

    private fun PaymentLedgerKit.sub(id: String) = checkNotNull(store.findSubscription(id))

    private fun PaymentLedgerKit.change(id: String, expected: Long, key: String = "chg-1", plan: String = "plus") = planChanges.change("acc_1", id, plan, expected, IdempotencyKey(key))

    private fun PaymentLedgerKit.fulfil() = runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

    @Test
    fun `going up charges only the difference for the rest of the period, switches the plan at once, and the next renewal is at the new price`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(15))
        val quote = kit.planChanges.preview("acc_1", active.id, "plus")

        val changed = kit.change(active.id, quote.charge.amount)

        val chargeCall = kit.gateway.chargeCalls.last()
        assertEquals(quote.charge.amount, chargeCall.amount.amount)
        assertEquals(true, quote.charge.amount in 1_400..1_600, "about half of the 3,000 gap, was ${quote.charge.amount}")
        assertEquals("plus", changed.planCode)
        assertEquals(6_900, changed.amount.amount)
        assertEquals(active.currentPeriodEnd, changed.currentPeriodEnd, "the period is kept")
        assertNull(changed.pendingPlanCode)
        assertEquals(PaymentOrderKind.SUBSCRIPTION_CHANGE, kit.store.listSubscriptionOrders(active.id, 1).single().kind)

        kit.time.set(checkNotNull(changed.currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.fulfil()
        assertEquals(6_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(2, kit.sub(active.id).periodSeq)
    }

    @Test
    fun `the same plan, a lower plan and a charge higher than the caller expected are refused and nothing is charged`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(1))
        val charges = kit.gateway.chargeCalls.size

        assertFailsWith<PaymentPlanChangeNotAllowedException> { kit.change(active.id, 9_999, plan = "basic") }
        assertFailsWith<PaymentPlanChangeChargeChangedException> { kit.change(active.id, 1) }
        assertEquals(charges, kit.gateway.chargeCalls.size)

        kit.plans.plans += SubscriptionPlan("yearly", "Yearly", PaymentAmount(69_000, "KRW"), "P1Y", 3)
        assertFailsWith<PaymentPlanChangeNotAllowedException> { kit.change(active.id, 99_999, plan = "yearly") }

        val up = kit.change(active.id, 9_999)
        assertEquals("plus", up.planCode)
        assertFailsWith<PaymentPlanChangeNotAllowedException> { kit.change(up.id, 9_999, key = "chg-2", plan = "basic") }
        assertFailsWith<PaymentPlanChangeNotAllowedException> { kit.change(up.id, 9_999, key = "chg-3", plan = "plus") }
        assertEquals(charges + 1, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `a declined change leaves the plan as it was, and the renewal still charges the old price`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(2))
        kit.gateway.script(ConfirmScript.Decline())

        assertFailsWith<PaymentDeclinedException> { kit.change(active.id, 3_000) }

        val after = kit.sub(active.id)
        assertEquals("basic", after.planCode)
        assertEquals(3_900, after.amount.amount)
        assertNull(after.pendingPlanCode)
        kit.time.set(checkNotNull(after.currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        assertEquals(3_900, kit.gateway.chargeCalls.last().amount.amount)
    }

    @Test
    fun `sending the same change twice charges once`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(2))
        val charges = kit.gateway.chargeCalls.size

        kit.change(active.id, 3_000)
        val replay = kit.change(active.id, 3_000)

        assertEquals("plus", replay.planCode)
        assertEquals(charges + 1, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `a change that is still unanswered at the period end blocks the renewal, and when it settles the renewal charges once at the new price`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.set(checkNotNull(active.currentPeriodEnd).minus(Duration.ofDays(1)))
        kit.gateway.script(ConfirmScript.TimeoutApplied())

        val pending = kit.change(active.id, 3_000)
        assertEquals("plus", pending.pendingPlanCode, "the change waits for the provider's answer - the plan is not switched yet")
        assertEquals("basic", pending.planCode)

        kit.time.set(checkNotNull(active.currentPeriodEnd).plusSeconds(1))
        val charges = kit.gateway.chargeCalls.size
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        assertEquals(charges, kit.gateway.chargeCalls.size, "no renewal charge while the change is in flight")

        kit.advance(kit.properties.reconcile.firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        kit.fulfil()
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.fulfil()

        val after = kit.sub(active.id)
        assertEquals("plus", after.planCode)
        assertEquals(charges + 1, kit.gateway.chargeCalls.size, "exactly one renewal")
        assertEquals(6_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(2, after.periodSeq)
    }

    @Test
    fun `a change cannot start while a renewal charge is in flight`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.set(checkNotNull(active.currentPeriodEnd))
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        val charges = kit.gateway.chargeCalls.size

        assertFailsWith<PaymentPlanChangeNotAllowedException> { kit.change(active.id, 9_999) }
        assertEquals(charges, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `restarting charges the whole new price, starts a new period now, and the renewal after it is charged once`() {
        val kit = newKit(Charge.FULL_RESTART)
        val active = kit.active()
        kit.time.set(checkNotNull(active.currentPeriodEnd)) // 한 번 갱신된 뒤에 바꾼다 — 새 주기의 번호가 옛 주기의 번호와 겹치지 않아야 한다
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.fulfil()
        assertEquals(2, kit.sub(active.id).periodSeq)
        kit.time.advance(Duration.ofDays(10))

        val changed = kit.change(active.id, 6_900)

        assertEquals(6_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(kit.time.now(), changed.anchorAt)
        assertEquals(1, changed.periodSeq)
        assertEquals(SubscriptionPeriod.parse("P1M").after(kit.time.now(), 1), changed.currentPeriodEnd)

        kit.time.set(checkNotNull(changed.currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        kit.fulfil()
        assertEquals(2, kit.sub(active.id).periodSeq)
        assertEquals(6_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(4, kit.gateway.chargeCalls.size, "first payment, one renewal, the change, the renewal after the restart")
    }

    @Test
    fun `after a plan change a full refund of the payment that bought the period still ends the subscription`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(2))
        kit.change(active.id, 3_000) // 변경 주문이 과금 주문 자리를 차지한다
        val initial = kit.store.listSubscriptionOrders(active.id, 5).single { it.kind == PaymentOrderKind.SUBSCRIPTION_INITIAL }

        kit.refunds.refund("op_1", initial.id, "OPERATOR", null, IdempotencyKey("r1"))
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY))

        val after = kit.sub(active.id)
        assertEquals(SubscriptionStatus.ENDED, after.status)
        assertEquals(SubscriptionEndReason.REFUNDED, after.endReason)
    }
}
