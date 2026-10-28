package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 시작 - 첫 기간 무료(토스 모양: 카드 등록 뒤 승인 없이 ACTIVE), 카카오페이 모양(0원 1회차), 단계 복사, 거절들. */
class SubscriptionDiscountStartTest {
    private val coupons = FakeCouponSource().apply {
        add("free1", listOf(DiscountStage(DiscountStageType.FREE, 0, null, 1), DiscountStage(DiscountStageType.PERCENT, 50, null, 2)))
        add("half", DiscountStage(DiscountStageType.PERCENT, 50, null, 3))
        add("oneTimeOnly", DiscountStage(DiscountStageType.PERCENT, 50), applies = { it.purpose == DiscountPurpose.ONE_TIME })
        add("basicOnly", DiscountStage(DiscountStageType.PERCENT, 50), applies = { it.planCode == "basic" })
    }
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons))
    private val auth = mapOf("authKey" to "auth_1")
    private fun claim(id: String) = listOf(DiscountClaim("coupon", id))

    private fun start(couponId: String?, plan: String = "plus", provider: String? = null) =
        kit.subscriptions.start("acc_1", plan, IdempotencyKey("k1"), provider = provider, discounts = couponId?.let(::claim).orEmpty())

    @Test
    fun `a free first period registers the card, never asks the provider for money, and the subscription is ACTIVE with the coupon used`() {
        val started = start("free1")
        assertEquals(0, kit.gateway.chargeCalls.size)
        assertEquals(0, coupons.calls.size, "starting only looks at the coupon - nothing is held until the first order exists")

        val active = kit.subscriptions.activate("acc_1", started.subscription.id, auth)

        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals(1, kit.gateway.issueCalls.size, "the card is still registered (a free trial needs a payment method)")
        assertEquals(0, kit.gateway.chargeCalls.size)
        assertEquals(0, kit.gateway.confirmCalls.size)
        val first = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(PaymentOrders.FREE_PROVIDER, first.provider)
        assertEquals(6_900, first.originalAmount)
        assertEquals(6_900, first.discountAmount)
        assertEquals("USED", coupons.stateOf("free1"))
        assertEquals(PaymentOrders.FREE_PROVIDER, first.provider)
        assertEquals(0, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).count { it.task.subjectId == first.id }, "no provider payment to look up")
    }

    @Test
    fun `the card registration is prepared with the first charge, and a paid first period is charged exactly that`() {
        kit.gateway.prepareReferences = true
        val started = start("half")

        assertEquals(3_450, kit.gateway.prepareCalls.single().amount.amount)

        val active = kit.subscriptions.activate("acc_1", started.subscription.id, auth)
        assertEquals(3_450, kit.gateway.chargeCalls.single().amount.amount)
        val first = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(3_450, first.amount.amount)
        assertEquals(3_450, first.discountAmount)
        assertEquals(OrderDiscountStatus.CONFIRMED, kit.store.listOrderDiscounts(listOf(first.id)).single().status)
    }

    @Test
    fun `a provider that takes the card and the first payment together is asked for 0 won and the order stays with that provider`() {
        kit.gateway.registering = true
        kit.gateway.prepareReferences = true
        val started = start("free1")
        assertEquals(0, kit.gateway.prepareCalls.single().amount.amount)

        val active = kit.subscriptions.activate("acc_1", started.subscription.id, mapOf("pg_token" to "pg_1"))

        assertEquals(0, kit.gateway.registerCalls.single().amount.amount)
        val first = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(kit.gateway.providerId, first.provider, "not an internal free order - the provider issued the billing key")
        assertEquals(SubscriptionStatus.ACTIVE, active.status)
    }

    @Test
    fun `the stages are copied - changing the coupon afterwards does not touch the running subscription`() {
        val started = start("half")
        val active = kit.subscriptions.activate("acc_1", started.subscription.id, auth)
        coupons.add("half", DiscountStage(DiscountStageType.PERCENT, 90))   // 정의를 고쳐도

        kit.time.set(checkNotNull(kit.store.findSubscription(active.id)!!.currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))

        val copy = checkNotNull(kit.store.findSubscriptionDiscount(active.id))
        assertEquals(listOf(DiscountStage(DiscountStageType.PERCENT, 50, null, 3)), copy.stages)
        assertEquals(listOf(3_450L, 3_450L), kit.gateway.chargeCalls.map { it.amount.amount })
    }

    @Test
    fun `a coupon whose first discount changed between the start and the first payment is refused instead of charging another price`() {
        val started = start("half")
        coupons.add("half", DiscountStage(DiscountStageType.PERCENT, 90))

        assertFailsWith<PaymentDiscountUnavailableException> { kit.subscriptions.activate("acc_1", started.subscription.id, auth) }
        assertEquals(0, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `a provider that renews by itself refuses a coupon, a coupon outside subscriptions or the plan is not applicable, and nothing is written`() {
        assertFailsWith<PaymentDiscountNotSupportedException> { start("half", plan = "basic", provider = kit.push.providerId) }
        assertFailsWith<PaymentDiscountNotApplicableException> { start("oneTimeOnly") }
        assertFailsWith<PaymentDiscountNotApplicableException> { start("basicOnly", plan = "plus") }
        assertFailsWith<PaymentDiscountNotApplicableException> { start("ghost") }
        assertEquals(0, kit.store.allOrders().size)
        assertNull(kit.store.findLatestSubscription("acc_1"))
    }

    @Test
    fun `a coupon taken by another order before the first payment fails the activation, the issued card is removed, and the subscription can start again`() {
        val started = start("half")
        coupons.heldBy("half", "ord_other")

        assertFailsWith<PaymentDiscountUnavailableException> { kit.subscriptions.activate("acc_1", started.subscription.id, auth) }

        assertEquals(1, kit.gateway.revokeCalls.size, "the billing key that was just issued is removed")
        assertEquals(SubscriptionStatus.PENDING, kit.store.findSubscription(started.subscription.id)!!.status)
        assertEquals(0, kit.store.allOrders().size)
    }
}
