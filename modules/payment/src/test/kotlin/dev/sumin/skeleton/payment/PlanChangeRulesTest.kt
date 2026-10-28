package dev.sumin.skeleton.payment

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 갈아타기 판정 한 곳: 등급 · 주기 · 통화 조합이 바로 / 새 주기 / 예약 / 거절 중 무엇인가. 순수 규칙. */
class PlanChangeRulesTest {
    private val t = Instant.parse("2026-01-01T00:00:00Z")

    private fun plan(code: String, level: Int, period: String, currency: String = "KRW") = SubscriptionPlan(code, code, PaymentAmount(1_000, currency), period, level)

    private fun subscriptionOn(plan: SubscriptionPlan) = PaymentSubscription(
        id = "sub_1", accountId = "acc_1", provider = "fake", providerMode = PaymentMode.TEST, status = SubscriptionStatus.ACTIVE, planCode = plan.code, amount = plan.amount,
        period = plan.period, anchorAt = t, periodSeq = 1, currentPeriodStart = t, currentPeriodEnd = t.plusSeconds(86_400), chargeOrderId = null, attempt = 1, customerKey = null, billingToken = null,
        cardIssuer = null, cardLast4 = null, activeKey = "acc_1", pendingPlanCode = null, pendingAmount = null, endReason = null, endedAt = null, accountErasedAt = null, createdAt = t, updatedAt = t,
    )

    private fun kind(current: SubscriptionPlan, target: SubscriptionPlan) = PlanChangeRules.classify(current, target, subscriptionOn(current))

    private val basicM = plan("basic", 1, "P1M")
    private val proM = plan("pro", 3, "P1M")
    private val basicY = plan("basic-yearly", 1, "P1Y")
    private val proY = plan("pro-yearly", 3, "P1Y")

    @Test
    fun `the six rows of the table`() {
        assertEquals(PlanChangeKind.IMMEDIATE_SAME_PERIOD, kind(basicM, proM), "higher tier, same period")
        assertEquals(PlanChangeKind.IMMEDIATE_NEW_PERIOD, kind(basicM, basicY), "month to year, same tier")
        assertEquals(PlanChangeKind.IMMEDIATE_NEW_PERIOD, kind(basicM, proY), "month to year, higher tier")
        assertEquals(PlanChangeKind.SCHEDULED, kind(proM, basicM), "lower tier, same period")
        assertEquals(PlanChangeKind.SCHEDULED, kind(proY, basicM), "lower tier, shorter period")
        assertEquals(PlanChangeKind.SCHEDULED, kind(basicY, basicM), "year to month, same tier")
        assertEquals(PlanChangeKind.SCHEDULED, kind(basicY, proM), "higher tier but shorter period waits for the renewal")
        assertEquals(PlanChangeKind.SCHEDULED, kind(proY, basicY), "lower tier, same yearly period")
    }

    @Test
    fun `the same plan, another currency and an unchanged tier and period are not allowed`() {
        assertEquals(PlanChangeKind.NOT_ALLOWED, kind(basicM, basicM))
        assertEquals(PlanChangeKind.NOT_ALLOWED, kind(basicM, plan("basic-usd", 1, "P1M", "USD")))
        assertEquals(PlanChangeKind.NOT_ALLOWED, kind(basicM, plan("basic-new-price", 1, "P1M")), "another price of the same tier and period")
    }

    @Test
    fun `the local test periods compare by length too`() {
        assertEquals(PlanChangeKind.IMMEDIATE_NEW_PERIOD, kind(plan("a", 1, "PT3M"), plan("a-y", 1, "PT36M")))
        assertEquals(PlanChangeKind.SCHEDULED, kind(plan("a-y", 1, "PT36M"), plan("a", 1, "PT3M")))
        assertEquals(PlanChangeKind.IMMEDIATE_SAME_PERIOD, kind(plan("a", 1, "PT3M"), plan("b", 2, "PT3M")))
    }
}
