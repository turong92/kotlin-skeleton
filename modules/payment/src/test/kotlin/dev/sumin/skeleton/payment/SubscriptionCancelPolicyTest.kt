package dev.sumin.skeleton.payment

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 해지·환불 정책에서 깨지면 사고 나는 것: 7일 철회 경계 · 위약금 내림 · 쓴 몫 계산(월 일할 · 연간 쓴 개월) · 낸 돈 상한. 순수 계산이라 저장소 없이 돈다. */
class SubscriptionCancelPolicyTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val monthEnd = Instant.parse("2026-01-31T00:00:00Z") // 30일 = 2,592,000초
    private val yearEnd = Instant.parse("2027-01-01T00:00:00Z")

    private fun subscription(period: String, amount: Long, end: Instant) = PaymentSubscription(
        id = "sub_1", accountId = "acc_1", provider = "fake", providerMode = PaymentMode.TEST, status = SubscriptionStatus.ACTIVE, planCode = "p", amount = PaymentAmount(amount, "KRW"),
        period = period, anchorAt = start, periodSeq = 1, currentPeriodStart = start, currentPeriodEnd = end, chargeOrderId = null, attempt = 1, customerKey = null, billingToken = null,
        cardIssuer = null, cardLast4 = null, activeKey = "acc_1", pendingPlanCode = null, pendingAmount = null, endReason = null, endedAt = null, accountErasedAt = null,
        createdAt = start, updatedAt = start,
    )

    private fun month(paid: Long = 3_900, now: Instant, used: Boolean = false) = CancelContext(subscription("P1M", paid, monthEnd), paid, now, monthlyPrice = null, used = used)

    private fun year(now: Instant, paid: Long = 69_000, monthly: Long? = 6_900, used: Boolean = true) = CancelContext(subscription("P1Y", paid, yearEnd), paid, now, monthly, used)

    private val korea0 = KoreaConsumerCancelPolicy(penaltyPercent = 0, withdrawalDays = 7)
    private val korea10 = KoreaConsumerCancelPolicy(penaltyPercent = 10, withdrawalDays = 7)

    @Test
    fun `the period-end-only policy never opens a mid-term cancel and refunds nothing`() {
        val quote = PeriodEndOnlyCancelPolicy.quote(month(now = start.plusSeconds(1)))

        assertFalse(quote.open)
        assertEquals(0, quote.refund)
        assertTrue(quote.lines.isEmpty())
    }

    @Test
    fun `the withdrawal lasts through the seventh date after the start date of the app zone, then it is the prorated rest`() {
        // 기간 시작은 서울 1월 1일 09:00 - 7일 뒤는 1월 8일. 그날 밤 23:59:59 까지는 철회, 1월 9일 00:00 부터는 아니다
        val lastNight = korea0.quote(month(now = Instant.parse("2026-01-08T14:59:59Z")))
        assertTrue(lastNight.withdrawal)
        assertEquals(3_900, lastNight.refund)
        assertEquals(listOf(CancelLine(CancelLineCode.PAID, 3_900)), lastNight.lines)

        val dawn = korea0.quote(month(now = Instant.parse("2026-01-08T15:00:00Z")))
        assertFalse(dawn.withdrawal)
        assertEquals(2_860, dawn.refund, "3,900 x 22 of 30 days, rounded down")
    }

    @Test
    fun `a plan raise paid mid-period is used only from the day it was paid, not over the whole period`() {
        // 3,900 을 기간 시작에, 차액 6,000 을 16일째(남은 15일)에 냈고 같은 날 해지 - 차액은 한 번도 안 썼다
        val raisedOn = Instant.parse("2026-01-16T00:00:00Z")
        val context = CancelContext(
            subscription("P1M", 9_900, monthEnd), paid = 9_900, now = raisedOn, monthlyPrice = null, used = true,
            payments = listOf(CancelPayment(3_900, start), CancelPayment(6_000, raisedOn)),
        )

        val quote = korea0.quote(context)

        assertEquals(7_950, quote.refund, "3,900 x 15/30 = 1,950 left + all 6,000 left (not 9,900 x 15/30 = 4,950)")
        assertEquals(-1_950, quote.lines.single { it.code == CancelLineCode.USED }.amount)
    }

    @Test
    fun `a used subscription gets no withdrawal even on the first day`() {
        val quote = korea0.quote(month(now = start.plus(Duration.ofDays(1)), used = true))

        assertFalse(quote.withdrawal)
        assertEquals(3_770, quote.refund, "29 of 30 days left")
    }

    @Test
    fun `the penalty is a share of what is left, rounded down`() {
        val whole = korea10.quote(month(now = start.plus(Duration.ofDays(1)), used = true))
        assertEquals(3_393, whole.refund, "3,770 left - 377 penalty")
        assertEquals(listOf(CancelLine(CancelLineCode.PAID, 3_900), CancelLine(CancelLineCode.USED, -130), CancelLine(CancelLineCode.PENALTY, -377)), whole.lines)

        val odd = korea10.quote(month(now = Instant.parse("2026-01-08T15:00:00Z"), paid = 3_905))
        assertEquals(2_577, odd.refund, "2,863 left - 286 penalty")
        assertEquals(-286, odd.lines.single { it.code == CancelLineCode.PENALTY }.amount, "3,905 x 22/30 = 2,863 left; floor(286.3) = 286")
    }

    @Test
    fun `a withdrawal pays no penalty`() {
        assertEquals(3_900, korea10.quote(month(now = start.plus(Duration.ofDays(3)))).refund)
    }

    @Test
    fun `a yearly payment charges the months begun at the monthly price of the same tier, not a discounted twelfth`() {
        fun refund(now: String) = korea10.quote(year(Instant.parse(now))).refund

        assertEquals(43_470, refund("2026-03-15T00:00:00Z"), "3 months begun = 20,700 used; 48,300 left - 4,830 penalty")
        assertEquals(43_470, refund("2026-03-01T00:00:00Z"), "the third month has begun at its first instant")
        assertEquals(49_680, refund("2026-02-28T23:59:59Z"), "2 months = 13,800 used; 55,200 left - 5,520 penalty")
    }

    @Test
    fun `a yearly payment without a monthly price of that tier falls back to the prorated share, and the used share never exceeds what was paid`() {
        assertEquals(34_783, korea0.quote(year(Instant.parse("2026-07-01T00:00:00Z"), monthly = null)).refund, "69,000 x 184/365 rounded down")

        val expensive = korea10.quote(year(Instant.parse("2026-12-15T00:00:00Z"), monthly = 9_000))
        assertEquals(0, expensive.refund, "12 months x 9,000 is more than was paid - nothing left, never negative")
        assertEquals(-69_000, expensive.lines.single { it.code == CancelLineCode.USED }.amount)
    }

    @Test
    fun `nothing paid this period means a zero refund`() {
        assertEquals(0, korea10.quote(month(paid = 0, now = start.plusSeconds(1))).refund)
    }

    @Test
    fun `the registry picks the policy by country and falls back to period-end-only`() {
        val policies = SubscriptionCancelPolicies(listOf(PeriodEndOnlyCancelPolicy, korea10), mapOf("KR" to "korea"))

        assertEquals("korea", policies.forCountry("KR").name)
        assertEquals("period-end-only", policies.forCountry("US").name)
        assertEquals("period-end-only", policies.forCountry(null).name)
    }
}
