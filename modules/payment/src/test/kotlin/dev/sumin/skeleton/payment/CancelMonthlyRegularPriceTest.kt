package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** 연간 구독의 쓴 몫은 같은 등급 월 플랜의 **정가**로 센다 - 행사가로 월 플랜이 싸게 팔리는 중에 연간을 해지해도 환불이 부풀지 않는다. */
class CancelMonthlyRegularPriceTest {
    private fun newKit(monthly: SubscriptionPlan) = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, Charge.PRORATED_DIFFERENCE, 0)),
        ),
    ).also {
        it.plans.plans.clear()
        it.plans.plans += monthly
        it.plans.plans += SubscriptionPlan("basic-yearly", "Basic yearly", PaymentAmount(39_000, "KRW"), "P1Y", 1, productCode = "basic")
    }

    private fun refundAfterTwoMonthsBegun(monthly: SubscriptionPlan): Long {
        val kit = newKit(monthly)
        val id = kit.subscriptions.start("acc_1", "basic-yearly", IdempotencyKey("k1"), "KR").subscription.id
        val active = kit.subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
        kit.time.advance(Duration.ofDays(40)) // 두 번째 달이 시작됐다 = 두 달 쓴 것으로 센다
        return kit.cancellations.preview("acc_1", active.id).quote.refund
    }

    @Test
    fun `the used share counts months at the regular price of the monthly plan, not at its sale price`() {
        val onSale = SubscriptionPlan("basic", "Basic", PaymentAmount(2_900, "KRW"), "P1M", 1, regularAmount = 3_900)
        val plain = SubscriptionPlan("basic", "Basic", PaymentAmount(3_900, "KRW"), "P1M", 1)

        assertEquals(28_080, refundAfterTwoMonthsBegun(onSale), "39,000 - 2 x 3,900 used = 31,200 left, minus the kit's 10% penalty")
        assertEquals(refundAfterTwoMonthsBegun(plain), refundAfterTwoMonthsBegun(onSale), "a sale on the monthly plan changes nothing")
    }
}
