package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 리뷰 지적의 돈 영역: 쿠폰은 한 개분 · 구독 단계의 최소 금액 · 충돌 표시 · 「결제한 적」. */
class DiscountReviewFixesTest {
    private val coupons = FakeCouponSource().apply {
        add("free", DiscountStage(DiscountStageType.FREE))
        add("full", DiscountStage(DiscountStageType.PERCENT, 100))
        add("half", DiscountStage(DiscountStageType.PERCENT, 50))
        add("leave50", DiscountStage(DiscountStageType.FIXED, 3_850))   // 3,900 -> 50
        add("fiveK", DiscountStage(DiscountStageType.FIXED, 5_000), applies = { it.amount >= 50_000 })
        add("third", listOf(DiscountStage(DiscountStageType.PERCENT, 10, null, 1), DiscountStage(DiscountStageType.FIXED, 3_850, null, 1)))
    }
    private fun claim(id: String) = listOf(DiscountClaim("coupon", id))

    @Test
    fun `one free or 100 percent coupon takes one unit off - a thousand units are not free`() {
        val kit = PaymentLedgerKit(catalog = PaymentCatalog { r -> PurchaseQuote(PaymentAmount(1_000L * r.quantity, "KRW"), "Units") }, discountSources = listOf(coupons))

        val preview = kit.orders.quote(QuoteCommand("acc_1", "cheer", "unit", quantity = 1000, discounts = claim("free")))
        assertEquals(1_000_000, preview.originalAmount)
        assertEquals(999_000, preview.amount, "one unit of 1,000 is off, not all")
        assertEquals(PaymentSettlement.PROVIDER, preview.settlement)

        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "unit", IdempotencyKey("k1"), quantity = 3, discounts = claim("full"))).order
        assertEquals(2_000, order.amount.amount)
        assertEquals(1_000, order.discountAmount)
        assertEquals(PaymentSettlement.PROVIDER, order.settlement)
    }

    @Test
    fun `the minimum and the scope of a coupon are judged on the order total, and a fixed amount comes off the total`() {
        val kit = PaymentLedgerKit(catalog = PaymentCatalog { r -> PurchaseQuote(PaymentAmount(10_000L * r.quantity, "KRW"), "Units") }, discountSources = listOf(coupons))

        val five = kit.orders.quote(QuoteCommand("acc_1", "cheer", "unit", quantity = 5, discounts = claim("fiveK")))
        assertEquals(45_000, five.amount, "5,000 off the 50,000 total - the coupon needs a 50,000 order and 10,000 x 5 is one")
        assertFailsWith<PaymentDiscountNotApplicableException> { kit.orders.quote(QuoteCommand("acc_1", "cheer", "unit", quantity = 4, discounts = claim("fiveK"))) }
    }

    @Test
    fun `a percent coupon is also taken off one unit, and a single unit behaves as before`() {
        val kit = PaymentLedgerKit(catalog = PaymentCatalog { r -> PurchaseQuote(PaymentAmount(1_000L * r.quantity, "KRW"), "Units") }, discountSources = listOf(coupons))

        assertEquals(2_500, kit.orders.quote(QuoteCommand("acc_1", "cheer", "unit", quantity = 3, discounts = claim("half"))).amount)
        assertEquals(500, kit.orders.quote(QuoteCommand("acc_1", "cheer", "unit", quantity = 1, discounts = claim("half"))).amount)
        assertEquals(0, kit.orders.quote(QuoteCommand("acc_1", "cheer", "unit", quantity = 1, discounts = claim("free"))).amount)
    }

    @Test
    fun `a subscription coupon with any stage under the provider minimum is refused at the start, a free stage is fine`() {
        val kit = PaymentLedgerKit(discountSources = listOf(coupons))
        kit.gateway.minimum = 100

        assertEquals(100L, assertFailsWith<PaymentDiscountBelowMinimumException> {
            kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), discounts = claim("leave50"))
        }.data.let { (it as Map<*, *>)["minimumAmount"] })
        assertEquals(0, kit.store.allOrders().size)
        // 첫 단계는 3,510 으로 통과하지만 둘째 단계가 50 원이다 - 시작에서 막는다
        assertFailsWith<PaymentDiscountBelowMinimumException> { kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k2"), discounts = claim("third")) }
        // 0원 단계는 결제사를 안 부르니 최소 금액과 상관없다
        kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k3"), discounts = claim("free"))
    }

    private fun lateOrderWithMark(mark: PaymentReviewReason): Pair<PaymentLedgerKit, PaymentOrder> {
        val kit = PaymentLedgerKit(discountSources = listOf(FakeCouponSource().also { it.add("c", DiscountStage(DiscountStageType.PERCENT, 30)) }))
        val first = kit.create(key = "k1", claims = claim("c")).order
        kit.advance(Duration.ofMinutes(30))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        kit.create(accountId = "acc_2", key = "k2", claims = claim("c"))   // 풀린 쿠폰을 다른 주문이 쥔다
        kit.gateway.authenticate("pay_late", first.id, first.amount)
        kit.gateway.settle("pay_late")
        kit.listener.failPaid(1)
        kit.refunds.sync("op_1", first.id)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))   // 지급이 한 번 던진다 - 아직 지급 전
        kit.store.inTransaction { tx -> tx.lockOrder(first.id)!!.let { tx.updateOrder(it.copy(reviewReason = mark)) } }
        kit.fulfillment.fulfil(first.id)
        return kit to first
    }

    @Test
    fun `a late paid order that lost its coupon replaces a stale fulfilment mark with the conflict`() {
        val (kit, first) = lateOrderWithMark(PaymentReviewReason.FULFILMENT_UNRESOLVED)

        assertEquals(PaymentReviewReason.DISCOUNT_CONFLICT, kit.order(first.id).reviewReason)
        assertTrue(kit.store.listEvents(first.id).any { it.type == PaymentEventType.REVIEW_FLAGGED && it.providerCode == "DISCOUNT_CONFLICT" })
    }

    @Test
    fun `a money mark stays, but the conflict is still written in the history so nobody misses it`() {
        val (kit, first) = lateOrderWithMark(PaymentReviewReason.AMOUNT_MISMATCH)

        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(first.id).reviewReason)
        assertTrue(kit.store.listEvents(first.id).any { it.type == PaymentEventType.REVIEW_FLAGGED && it.providerCode == "DISCOUNT_CONFLICT" })
    }

    @Test
    fun `a first period of 0 won with the provider does not make the account a past buyer, a real payment does`() {
        val kit = PaymentLedgerKit(discountSources = listOf(coupons))
        kit.gateway.registering = true
        kit.gateway.prepareReferences = true
        val started = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), discounts = claim("free"))
        kit.subscriptions.activate("acc_1", started.subscription.id, mapOf("pg_token" to "pg_1"))

        assertFalse(kit.store.hasPaidOrder("acc_1"), "nothing was paid to the provider")
    }

    @Test
    fun `a renewal guessed free outside the lock but paid inside it claims nothing and wakes itself up, so the provider is checked first`() {
        val kit = PaymentLedgerKit(discountSources = listOf(coupons.also { it.add("mid", listOf(DiscountStage(DiscountStageType.PERCENT, 50, null, 1), DiscountStage(DiscountStageType.FREE, 0, null, 1), DiscountStage(DiscountStageType.PERCENT, 50, null, 3))) }))
        val id = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), discounts = claim("mid")).subscription.id
        kit.subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
        kit.time.set(checkNotNull(kit.store.findSubscription(id)!!.currentPeriodEnd))
        // 가드는 락 밖에서 불린다 - 어림(무료)이 끝난 뒤, 락을 잡기 전에 일정이 한 칸 더 나아간 것처럼 만든다
        val racing = SubscriptionRenewTaskHandler(
            kit.store, kit.router, kit.plans, dev.sumin.skeleton.payment.testing.ReversibleTestEncryptor, kit.lifecycle, kit.charges,
            SubscriptionRenewalGuard {
                kit.store.inTransaction { tx -> tx.lockSubscription(id); tx.updateSubscriptionDiscount(checkNotNull(tx.subscriptionDiscount(id)).copy(periodsUsed = 2)) }
                true
            },
            kit.writer, kit.properties.subscription.renewal.retryInterval, kit.subscriptionDiscounts,
        )
        val ordersBefore = kit.store.listSubscriptionOrders(id, 10).size
        val chargesBefore = kit.gateway.chargeCalls.size
        val wakeUpsBefore = kit.tasks.all(PaymentTaskTypes.SUBSCRIPTION_RENEW).size

        racing.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, id, 1, 12))

        assertEquals(ordersBefore, kit.store.listSubscriptionOrders(id, 10).size, "nothing was claimed")
        assertEquals(chargesBefore, kit.gateway.chargeCalls.size)
        assertEquals(wakeUpsBefore + 1, kit.tasks.all(PaymentTaskTypes.SUBSCRIPTION_RENEW).size, "the renewal is woken again")

        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, id, 2, 12))
        assertEquals(chargesBefore + 1, kit.gateway.chargeCalls.size, "the next step charges after the normal provider check")
    }
}
