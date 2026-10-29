package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.FakeHostedPushGateway
import dev.sumin.skeleton.payment.testing.FakeHostedPushGateway.Apply
import dev.sumin.skeleton.payment.testing.FakePushGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 결제사가 갱신하는 구독(Paddle 모양)의 플랜 변경 - 올리기만 한다. 판정은 [PlanChangeRules], 금액은 결제사가 계산한다.
 * 지키는 것: 결제사가 바꾼 뒤에만 플랜이 바뀐다 · 거절이면 그대로 · 본 금액보다 크면 결제사를 바꾸지 않는다 · 재전송은 그대로 · 응답 유실은 웹훅이 맞춘다 · 내리기는 결제사를 부르지 않고 거절한다.
 */
class ProviderPlanChangeTest {
    private val push = FakeHostedPushGateway()
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private var seq = 0

    private fun kitOf(gateway: FakePushGateway = push) = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, PaymentProperties.Subscription.PlanChange.Charge.PRORATED_DIFFERENCE)),
        ),
        push = gateway,
    )

    private val kit = kitOf()

    /** 결제사가 USD 로 환산해 결제한 `basic`(플랜 표는 KRW) 구독 하나. */
    private fun active(planCode: String = "basic"): PaymentSubscription {
        val pending = kit.subscriptions.start("acc_1", planCode, IdempotencyKey("k1"), provider = "paddle").subscription
        feed(
            PushedPayment(
                eventId = "evt_${++seq}", occurredAt = t0, providerPaymentId = checkNotNull(pending.firstTransactionId), total = PaymentAmount(990, "USD"), tax = 90, paidAt = t0,
                ourAccountId = "acc_1", providerSubscriptionId = "psub_1", periodStart = t0, periodEnd = t0.plusSeconds(31 * 86400), items = listOf(PushedItem("pri_$planCode", 1)),
            ),
        )
        return sub(pending.id)
    }

    private fun feed(event: PushedPaymentEvent) {
        kit.deliver(push.delivery(event), token = null, provider = "paddle")
        kit.runWebhooks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
    }

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    private fun change(id: String, expected: Long, plan: String = "plus") = kit.planChanges.change("acc_1", id, plan, expected, IdempotencyKey("chg-1"))

    @Test
    fun `a provider managed upgrade switches plan, amount and period only after the provider applied it`() {
        val line = active()
        val moved = t0.plusSeconds(86400)
        push.quote = push.quote.copy(periodStart = moved, periodEnd = moved.plusSeconds(31 * 86400), restartsPeriod = true)
        val reportedStart = moved.plusSeconds(5) // 적용 응답의 새 기간이 있으면 그것이 장부 기간이다(견적과 수 초 다르다)
        push.appliedPeriod = HostedPlanApplied(reportedStart, reportedStart.plusSeconds(31 * 86400))

        val preview = kit.planChanges.preview("acc_1", line.id, "plus")
        assertEquals(PaymentAmount(500, "USD"), preview.charge)
        assertEquals(PaymentAmount(300, "USD"), preview.credit)
        assertEquals(PaymentAmount(1_990, "USD"), preview.plan.amount, "the amount is what the provider will charge from now on")
        assertEquals(PlanChangeKind.IMMEDIATE_SAME_PERIOD, preview.kind)
        assertEquals("basic", sub(line.id).planCode, "a preview changes nothing")

        val changed = change(line.id, expected = 500)

        assertEquals(HostedPlanChange("psub_1", "plus", PlanChangeKind.IMMEDIATE_SAME_PERIOD), push.applies.single())
        assertEquals("plus", changed.planCode)
        assertEquals(PaymentAmount(1_990, "USD"), changed.amount)
        assertEquals(reportedStart, changed.currentPeriodStart)
        assertEquals(line.periodSeq + 1, changed.periodSeq)
        feed(PushedSubscription("evt_${++seq}", t0.plusSeconds(86401), "psub_1", PushedSubscriptionStatus.ACTIVE, periodStart = reportedStart, periodEnd = reportedStart.plusSeconds(31 * 86400)))
        assertEquals(changed.periodSeq, sub(line.id).periodSeq, "the webhook for the same period does not count it again")
    }

    @Test
    fun `a charge above the expected charge is refused before the provider is asked to apply`() {
        val line = active()
        assertFailsWith<PaymentPlanChangeChargeChangedException> { change(line.id, expected = 499) }

        assertTrue(push.applies.isEmpty())
        assertEquals("basic", sub(line.id).planCode)
    }

    @Test
    fun `a declined change leaves the plan as it was`() {
        val line = active()
        push.apply = Apply.DECLINED

        assertFailsWith<PaymentDeclinedException> { change(line.id, expected = 500) }

        assertEquals("basic", sub(line.id).planCode)
        assertEquals(PaymentAmount(990, "USD"), sub(line.id).amount)
    }

    @Test
    fun `sending the same change again after it applied answers the subscription as it is`() {
        val line = active()
        val first = change(line.id, expected = 500)

        val again = change(line.id, expected = 500)

        assertEquals(first.planCode, again.planCode)
        assertEquals(1, push.applies.size, "the provider is not asked twice")
    }

    @Test
    fun `an unknown answer leaves the plan, and the subscription updated webhook switches it later`() {
        val line = active()
        push.apply = Apply.UNKNOWN

        assertFailsWith<IllegalStateException> { change(line.id, expected = 500) }
        assertEquals("basic", sub(line.id).planCode)

        val newStart = t0.plusSeconds(60)
        feed(PushedSubscription("evt_${++seq}", newStart, "psub_1", PushedSubscriptionStatus.ACTIVE, periodStart = newStart, periodEnd = newStart.plusSeconds(365 * 86400), items = listOf(PushedItem("pri_plus", 1))))
        assertEquals("plus", sub(line.id).planCode)
        assertEquals(newStart, sub(line.id).currentPeriodStart, "the webhook also brings the new provider period")
        assertEquals(line.periodSeq + 1, sub(line.id).periodSeq)
    }

    @Test
    fun `a subscription paid in a converted currency compares plans in the plan table currency`() {
        val line = active()
        kit.plans.plans += SubscriptionPlan("euro", "Euro", PaymentAmount(9_000, "EUR"), "P1M", 3)

        assertEquals(PaymentAmount(500, "USD"), kit.planChanges.preview("acc_1", line.id, "plus").charge, "KRW table plan, USD subscription")
        assertFailsWith<PaymentPlanChangeNotAllowedException> { kit.planChanges.preview("acc_1", line.id, "euro") }
    }

    @Test
    fun `a provider without the plan change capability is refused`() {
        val plain = kitOf(FakePushGateway())
        val pending = plain.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "paddle").subscription
        plain.deliver(plain.push.delivery(PushedPayment("evt_p", t0, checkNotNull(pending.firstTransactionId), PaymentAmount(990, "USD"), 90, t0, providerSubscriptionId = "psub_1", periodStart = t0, periodEnd = t0.plusSeconds(86400), items = listOf(PushedItem("pri_basic", 1)))), token = null, provider = "paddle")
        plain.runWebhooks()

        assertFailsWith<PaymentPlanChangeNotAllowedException> { plain.planChanges.preview("acc_1", pending.id, "plus") }
        assertFailsWith<PaymentPlanChangeNotAllowedException> { plain.planChanges.change("acc_1", pending.id, "plus", 0, IdempotencyKey("chg-1")) }
    }

    @Test
    fun `a downgrade or a shorter period is refused for a provider subscription and the provider is not asked`() {
        kit.plans.plans += SubscriptionPlan("plus-year", "Plus Year", PaymentAmount(69_000, "KRW"), "P1Y", 2)
        val line = active("plus-year")

        val shorter = assertFailsWith<PaymentPlanChangeDowngradeUnsupportedException> { kit.planChanges.preview("acc_1", line.id, "plus") }
        assertEquals(PaymentErrorCode.PLAN_CHANGE_DOWNGRADE_UNSUPPORTED, shorter.errorCode)
        assertFailsWith<PaymentPlanChangeDowngradeUnsupportedException> { kit.planChanges.preview("acc_1", line.id, "basic") }
        assertFailsWith<PaymentPlanChangeDowngradeUnsupportedException> { kit.planChanges.change("acc_1", line.id, "basic", 0, IdempotencyKey("chg-2")) }

        assertTrue(push.previews.isEmpty() && push.applies.isEmpty())
        assertEquals("plus-year", sub(line.id).planCode)
    }

    @Test
    fun `a webhook that switched only the plan first does not stop the change from putting the provider's recurring amount on the line`() {
        val line = active()
        // 결제사가 적용하는 동안 금액을 싣지 않은 구독 사건이 먼저 장부에 닿는다
        push.whileApplying = { feed(PushedSubscription("evt_${++seq}", t0.plusSeconds(60), "psub_1", PushedSubscriptionStatus.ACTIVE, items = listOf(PushedItem("pri_plus", 1)))) }

        val changed = kit.planChanges.change("acc_1", line.id, "plus", 500, IdempotencyKey("chg-1"))

        assertEquals(PaymentAmount(1_990, "USD"), changed.amount)
        assertEquals(PaymentAmount(1_990, "USD"), sub(line.id).amount)
    }

    @Test
    fun `a quote in another currency than the subscription is refused before the provider is asked to apply`() {
        val line = active()
        push.quote = push.quote.copy(recurring = PaymentAmount(1_990, "EUR"))

        assertFailsWith<PaymentPlanChangeNotAllowedException> { change(line.id, expected = 500) }
        assertTrue(push.applies.isEmpty())
    }
}
