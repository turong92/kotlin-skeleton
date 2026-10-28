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
 * 갈아타기 예약(등급 내리기 · 주기 줄이기)에서 깨지면 사고 나는 것: 예약은 결제 없이 적어 둘 뿐이다 · 갱신이 같은 트랜잭션에서 적용하고 **예약한 때의 금액**으로 과금한다 ·
 * 실패한 갱신의 재시도도 새 금액이다 · 예약 취소 · 올리기 · 해지 예약이 예약을 지운다. 메모리 저장소 + 빌링 대역.
 */
class ScheduledPlanChangeTest {
    private fun newKit() = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, Charge.PRORATED_DIFFERENCE, 0)),
        ),
    ).also {
        it.plans.plans += SubscriptionPlan("pro", "Pro", PaymentAmount(9_900, "KRW"), "P1M", 3)
        it.plans.plans += SubscriptionPlan("plus-yearly", "Plus yearly", PaymentAmount(69_000, "KRW"), "P1Y", 2, productCode = "plus")
    }

    private fun PaymentLedgerKit.active(plan: String = "plus"): PaymentSubscription {
        val id = subscriptions.start("acc_1", plan, IdempotencyKey("k1")).subscription.id
        return subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
    }

    private fun PaymentLedgerKit.sub(id: String) = checkNotNull(store.findSubscription(id))

    private fun PaymentLedgerKit.schedule(id: String, plan: String, key: String = "chg-1") = planChanges.change("acc_1", id, plan, 0, IdempotencyKey(key))

    private fun PaymentLedgerKit.renew() = runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))

    @Test
    fun `a lower tier is only written down - nothing is charged, nothing changes now, and the preview says it happens at the period end`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(3))
        val charges = kit.gateway.chargeCalls.size

        val quote = kit.planChanges.preview("acc_1", active.id, "basic")
        assertEquals(listOf(PlanChangeKind.SCHEDULED, 0L, active.currentPeriodEnd), listOf(quote.kind, quote.charge.amount, quote.effectiveAt))

        val scheduled = kit.schedule(active.id, "basic")

        assertEquals(listOf("plus", 6_900L, "basic", 3_900L, "P1M"), listOf(scheduled.planCode, scheduled.amount.amount, scheduled.scheduledPlanCode, scheduled.scheduledAmount, scheduled.scheduledPeriod))
        assertEquals(charges, kit.gateway.chargeCalls.size)
        assertEquals(1, kit.store.listSubscriptionOrders(active.id, 5).size, "no change order")
    }

    @Test
    fun `at the renewal the schedule is applied and charged at the amount written when it was scheduled, even if the table changed since`() {
        val kit = newKit()
        val active = kit.active()
        kit.schedule(active.id, "basic")
        kit.plans.plans.replaceAll { if (it.code == "basic") it.copy(amount = PaymentAmount(9_999, "KRW")) else it }   // 그 사이 가격표가 바뀌었다
        kit.time.set(checkNotNull(active.currentPeriodEnd))

        kit.renew()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        val after = kit.sub(active.id)
        assertEquals(3_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(listOf("basic", 3_900L, 2, null), listOf(after.planCode, after.amount.amount, after.periodSeq, after.scheduledPlanCode))
        assertEquals(SubscriptionStatus.ACTIVE, after.status)
    }

    @Test
    fun `year to month restarts the count at the old period end, and the first month is the renewal payment`() {
        val kit = newKit()
        val active = kit.active("plus-yearly")
        val oldEnd = checkNotNull(active.currentPeriodEnd)
        kit.schedule(active.id, "plus")
        kit.time.set(oldEnd)

        kit.renew()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        val after = kit.sub(active.id)
        assertEquals(6_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(listOf("plus", "P1M", oldEnd, 1), listOf(after.planCode, after.period, after.anchorAt, after.periodSeq))
        assertEquals(oldEnd, after.currentPeriodStart)
        assertEquals(SubscriptionPeriod.parse("P1M").after(oldEnd, 1), after.currentPeriodEnd)
    }

    @Test
    fun `a declined renewal leaves the new plan applied, so the retry charges the new amount`() {
        val kit = newKit()
        val active = kit.active()
        kit.schedule(active.id, "basic")
        kit.time.set(checkNotNull(active.currentPeriodEnd))
        kit.gateway.script(ConfirmScript.Decline())

        kit.renew()

        val pastDue = kit.sub(active.id)
        assertEquals(listOf(SubscriptionStatus.PAST_DUE, "basic", 3_900L, null), listOf(pastDue.status, pastDue.planCode, pastDue.amount.amount, pastDue.scheduledPlanCode))
        kit.advance(kit.properties.subscription.renewal.retryInterval)
        kit.renew()
        assertEquals(3_900, kit.gateway.chargeCalls.last().amount.amount, "the retry is at the new amount")
    }

    @Test
    fun `taking the schedule back is final and pressing twice is the same, and the renewal then charges the old plan`() {
        val kit = newKit()
        val active = kit.active()
        kit.schedule(active.id, "basic")

        assertNull(kit.planChanges.cancelScheduled("acc_1", active.id).scheduledPlanCode)
        assertNull(kit.planChanges.cancelScheduled("acc_1", active.id).scheduledPlanCode)
        kit.time.set(checkNotNull(active.currentPeriodEnd))
        kit.renew()

        assertEquals(6_900, kit.gateway.chargeCalls.last().amount.amount)
        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.planChanges.cancelScheduled("acc_other", active.id) }
    }

    @Test
    fun `scheduling again replaces the schedule, and an upgrade or a cancel at the period end wipes it`() {
        val kit = newKit()
        val active = kit.active()
        kit.schedule(active.id, "basic")
        kit.time.advance(Duration.ofDays(1))
        assertEquals("basic", kit.sub(active.id).scheduledPlanCode)

        val up = kit.planChanges.change("acc_1", active.id, "pro", 9_999, IdempotencyKey("chg-up"))
        assertEquals(listOf("pro", null), listOf(up.planCode, up.scheduledPlanCode), "the upgrade wiped the lower-tier schedule")

        kit.schedule(active.id, "plus", key = "chg-2")
        assertEquals("plus", kit.sub(active.id).scheduledPlanCode)
        val canceling = kit.subscriptions.cancel("acc_1", active.id)
        assertEquals(listOf(SubscriptionStatus.CANCELING, null), listOf(canceling.status, canceling.scheduledPlanCode), "ending at the period end wipes the schedule")
    }

    @Test
    fun `the monthly list price written at the start follows the schedule too`() {
        val kit = newKit()
        kit.plans.plans += SubscriptionPlan("basic-yearly", "Basic yearly", PaymentAmount(39_000, "KRW"), "P1Y", 1, productCode = "basic")
        val active = kit.active("plus-yearly")
        assertEquals(6_900L, active.regularMonthlyAmount)
        kit.schedule(active.id, "basic-yearly")
        kit.time.set(checkNotNull(active.currentPeriodEnd))

        kit.renew()

        assertEquals(3_900L, kit.sub(active.id).regularMonthlyAmount, "the lower tier's monthly list price is written when the schedule is applied")
    }
}
