package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrderDiscountTest {
    private val coupons = FakeCouponSource().apply {
        add("c30", DiscountStage(DiscountStageType.PERCENT, 30))
        add("c30b", DiscountStage(DiscountStageType.PERCENT, 30))
        add("cap", DiscountStage(DiscountStageType.FIXED, 2950))
    }
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons))
    private fun claim(id: String) = listOf(DiscountClaim("coupon", id))

    @Test
    fun `a 30 percent coupon charges the provider 7000, the line keeps the original and the discount, and the coupon is reserved`() {
        val created = kit.create(itemRef = "cheer-5000", claims = claim("c30"))   // 5,000 -> 3,500
        val order = created.order

        assertEquals(PaymentAmount(3500, "KRW"), order.amount)
        assertEquals(5000, order.originalAmount)
        assertEquals(1500, order.discountAmount)
        assertEquals(PaymentSettlement.PROVIDER, order.settlement)
        assertEquals(3500, kit.gateway.checkoutCalls.single().amount.amount, "the checkout gets the discounted amount")
        val line = created.discounts.single()
        assertEquals(OrderDiscountStatus.RESERVED, line.status)
        assertEquals(listOf(1500L), created.discounts.map { it.amount })
        assertEquals("RESERVED", coupons.stateOf("c30"))
        assertEquals(order.id, coupons.holderOf("c30"))
        assertEquals(created.discounts, kit.store.listOrderDiscounts(listOf(order.id)))
    }

    @Test
    fun `ten thousand won with 30 percent is seven thousand and the preview shows the same amount`() {
        val big = PaymentLedgerKit(catalog = PaymentCatalog { PurchaseQuote(PaymentAmount(10_000, "KRW"), "Big") }, discountSources = listOf(coupons))
        val preview = big.orders.quote(QuoteCommand("acc_1", "cheer", "any", discounts = claim("c30")))
        val created = big.orders.create(CreateOrderCommand("acc_1", "cheer", "any", IdempotencyKey("k"), discounts = claim("c30")))

        assertEquals(OrderQuote(10_000, 7_000, "KRW", listOf(DiscountDraft("coupon", "c30", "Coupon c30", 3_000)), PaymentSettlement.PROVIDER), preview)
        assertEquals(7_000, created.order.amount.amount)
        assertEquals(10_000, created.order.originalAmount)
        assertEquals(3_000, created.order.discountAmount)
        assertEquals("AVAILABLE", coupons.stateOf("c30b"), "a preview holds nothing")
        assertEquals(7_000, big.gateway.checkoutCalls.single().amount.amount)
    }

    @Test
    fun `a preview holds nothing and the same key again replays the order without reserving twice`() {
        kit.orders.quote(QuoteCommand("acc_1", "cheer", "cheer-3000", discounts = claim("c30")))
        assertEquals(emptyList(), coupons.calls)

        val first = kit.create(key = "same", claims = claim("c30"))
        val again = kit.create(key = "same", claims = claim("c30"))

        assertEquals(first.order.id, again.order.id)
        assertTrue(again.replayed)
        assertEquals(first.discounts, again.discounts)
        assertEquals(1, coupons.calls.count { it.startsWith("reserve") })
    }

    @Test
    fun `a discount without any discount source, an unknown kind or two discounts is DISCOUNT_NOT_APPLICABLE and writes nothing`() {
        val plain = PaymentLedgerKit()
        assertFailsWith<PaymentDiscountNotApplicableException> { plain.create(claims = claim("c30")) }
        assertFailsWith<PaymentDiscountNotApplicableException> { kit.create(key = "a", claims = listOf(DiscountClaim("points", "x"))) }
        assertFailsWith<PaymentDiscountNotApplicableException> { kit.create(key = "b", claims = listOf(DiscountClaim("coupon", "c30"), DiscountClaim("coupon", "c30b"))) }
        assertFailsWith<PaymentDiscountNotApplicableException> { kit.create(key = "c", claims = claim("ghost")) }
        assertEquals(0, plain.store.allOrders().size + kit.store.allOrders().size)
        assertEquals("AVAILABLE", coupons.stateOf("c30"))
    }

    @Test
    fun `a coupon that is already held is DISCOUNT_UNAVAILABLE and the second order is not written`() {
        kit.create(key = "one", claims = claim("c30"))

        assertFailsWith<PaymentDiscountUnavailableException> { kit.create(accountId = "acc_2", key = "two", claims = claim("c30")) }

        assertEquals(1, kit.store.allOrders().size)
    }

    @Test
    fun `one coupon and two simultaneous orders - exactly one is reserved, the other is DISCOUNT_UNAVAILABLE`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            val futures = listOf("acc_1" to "k1", "acc_2" to "k2").map { (account, key) ->
                pool.submit<Throwable?> { start.await(); runCatching { kit.create(accountId = account, key = key, claims = claim("c30")) }.exceptionOrNull() }
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, results.count { it == null })
        assertTrue(results.filterNotNull().single() is PaymentDiscountUnavailableException)
        assertEquals(1, kit.store.allOrders().size)
        assertEquals(kit.store.allOrders().single().id, coupons.holderOf("c30"))
    }

    @Test
    fun `a discount that brings the amount under the provider minimum is DISCOUNT_BELOW_MINIMUM, a free amount is not`() {
        kit.gateway.minimum = 1_000
        coupons.add("leave50", DiscountStage(DiscountStageType.FIXED, 2950))   // 3,000 -> 50
        assertEquals(1000L, assertFailsWith<PaymentDiscountBelowMinimumException> { kit.create(key = "a", claims = claim("leave50")) }.data.let { (it as Map<*, *>)["minimumAmount"] })
        assertEquals(0, kit.store.allOrders().size)
        assertEquals("AVAILABLE", coupons.stateOf("leave50"))
        // 할인이 없는 일반 주문에는 이 검사가 걸리지 않는다(지금까지와 같다)
        assertEquals(PaymentOrderStatus.PENDING, kit.create(key = "plain").order.status)
    }

    @Test
    fun `a provider that sets the amount itself refuses discounts`() {
        val failure = runCatching { kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k"), provider = kit.push.providerId, discounts = claim("c30"))) }.exceptionOrNull()

        assertTrue(failure is PaymentDiscountNotSupportedException, failure.toString())
        assertEquals("AVAILABLE", coupons.stateOf("c30"))
        assertEquals(0, kit.store.allOrders().size)
    }
}
