package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountConfirmOutcome
import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountRejectedException
import dev.sumin.skeleton.common.discount.DiscountRejection
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.common.discount.DiscountTarget
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 쿠폰이 결제 할인 수단으로 쓸 수 있는지 판정하는 것과 예약 · 확정 · 해제 · 복원의 상태 전이 */
class CouponDiscountSourceTest {
    private val store = FakeCouponStore()
    private val time = MutableTime()
    private val source = CouponDiscountSource(store, time)

    private fun target(
        accountId: String = "acc_1",
        itemType: String = "cheer",
        itemRef: String = "item",
        planCode: String? = null,
        amount: Long = 10_000,
        currency: String = "KRW",
        purpose: DiscountPurpose = DiscountPurpose.ONE_TIME,
        firstPurchase: Boolean = true,
        productCode: String? = null,
    ) = DiscountTarget(accountId, itemType, itemRef, planCode, amount, currency, purpose, firstPurchase, productCode)

    private fun given(def: CouponDefinition = definition(), c: Coupon = coupon()): DiscountClaim {
        store.definitions[def.id] = def
        store.coupons[c.id] = c
        return DiscountClaim("coupon", c.id)
    }

    private fun assertRejected(reason: DiscountRejection, claim: DiscountClaim, target: DiscountTarget) {
        assertEquals(reason, assertFailsWith<DiscountRejectedException> { source.quote(claim, target) }.reason)
        assertEquals(reason, assertFailsWith<DiscountRejectedException> { source.reserve(claim, target, "ord_1") }.reason)
        assertEquals(CouponStatus.AVAILABLE, store.coupons.values.first().status, "a rejected claim holds nothing")
    }

    @Test
    fun `the contract identity`() {
        assertEquals("coupon", source.kind)
        assertEquals(100, source.order)
    }

    @Test
    fun `a matching coupon quotes the definition's stages without holding anything`() {
        val claim = given()
        val offer = source.quote(claim, target())
        assertEquals("cpn_1", offer.sourceRef)
        assertEquals("30% off", offer.label)
        assertEquals(DiscountStageType.PERCENT, offer.stages.single().type)
        assertEquals(CouponStatus.AVAILABLE, store.coupons.getValue("cpn_1").status)
    }

    @Test
    fun `item type and plan outside the scope are not applicable`() {
        val claim = given(definition(scope = CouponScope(itemTypes = listOf("cheer"), planCodes = listOf("pro"))))
        assertRejected(DiscountRejection.NOT_APPLICABLE, claim, target(itemType = "book", planCode = "pro"))
        assertRejected(DiscountRejection.NOT_APPLICABLE, claim, target(planCode = "basic"))
        assertRejected(DiscountRejection.NOT_APPLICABLE, claim, target(planCode = null))
        source.quote(claim, target(planCode = "pro"))
    }

    @Test
    fun `a scope on a plan code also fits every price of that product, and a scope on an item ref fits every item of that product`() {
        val plan = given(definition(scope = CouponScope(planCodes = listOf("basic"))))
        source.quote(plan, target(planCode = "basic-yearly", productCode = "basic"))
        source.quote(plan, target(planCode = "basic"))
        assertRejected(DiscountRejection.NOT_APPLICABLE, plan, target(planCode = "pro-yearly", productCode = "pro"))
        assertRejected(DiscountRejection.NOT_APPLICABLE, plan, target(planCode = "basic-yearly", productCode = null))

        val item = given(definition(scope = CouponScope(itemRefs = listOf("sticker"))), coupon("cpn_2"))
        source.quote(item, target(itemRef = "sticker-2026", productCode = "sticker"))
        assertRejected(DiscountRejection.NOT_APPLICABLE, item, target(itemRef = "sticker-2026", productCode = "poster"))
    }

    @Test
    fun `the purpose outside the scope is not applicable`() {
        val claim = given(definition(scope = CouponScope(purposes = listOf(DiscountPurpose.SUBSCRIPTION))))
        assertRejected(DiscountRejection.NOT_APPLICABLE, claim, target(purpose = DiscountPurpose.ONE_TIME))
    }

    @Test
    fun `an amount under the minimum is not applicable`() {
        val claim = given(definition(currency = "KRW", minAmount = 10_000))
        assertRejected(DiscountRejection.NOT_APPLICABLE, claim, target(amount = 9_999))
        source.quote(claim, target(amount = 10_000))
    }

    @Test
    fun `a fixed coupon in another currency is not applicable`() {
        val claim = given(definition(stages = listOf(DiscountStage(DiscountStageType.FIXED, 1000, null, 1)), currency = "KRW"))
        assertRejected(DiscountRejection.NOT_APPLICABLE, claim, target(currency = "USD"))
    }

    @Test
    fun `first purchase only is not applicable once the account has paid`() {
        val claim = given(definition(firstPurchaseOnly = true))
        assertRejected(DiscountRejection.NOT_APPLICABLE, claim, target(firstPurchase = false))
        source.quote(claim, target(firstPurchase = true))
    }

    @Test
    fun `someone else's coupon, a used one, a held one and an expired one are unavailable`() {
        val own = given()
        assertRejected(DiscountRejection.UNAVAILABLE, own, target(accountId = "acc_2"))
        assertRejected(DiscountRejection.UNAVAILABLE, DiscountClaim("coupon", "cpn_none"), target())

        for (status in listOf(CouponStatus.USED, CouponStatus.RESERVED, CouponStatus.REVOKED, CouponStatus.EXPIRED)) {
            store.coupons["cpn_1"] = coupon(status = status)
            assertEquals(DiscountRejection.UNAVAILABLE, assertFailsWith<DiscountRejectedException> { source.quote(own, target()) }.reason, "$status")
        }

        store.coupons["cpn_1"] = coupon(expiresAt = NOW.minusSeconds(1))
        assertEquals(DiscountRejection.UNAVAILABLE, assertFailsWith<DiscountRejectedException> { source.reserve(own, target(), "ord_1") }.reason)
    }

    @Test
    fun `a coupon can not be quoted or reserved before its use start, and can from that instant`() {
        val start = NOW.plus(Duration.ofDays(1))
        val claim = given(definition(validFrom = start))
        assertRejected(DiscountRejection.UNAVAILABLE, claim, target())

        time.current = start.minusSeconds(1)
        assertRejected(DiscountRejection.UNAVAILABLE, claim, target())

        time.current = start
        source.quote(claim, target())
        source.reserve(claim, target(), "ord_1")
        assertEquals(CouponStatus.RESERVED, store.coupons.getValue("cpn_1").status)
    }

    @Test
    fun `a paused or archived definition does not take back a coupon already issued`() {
        val claim = given(definition(status = CouponDefinitionStatus.ARCHIVED))
        source.reserve(claim, target(), "ord_1")
        assertEquals(CouponStatus.RESERVED, store.coupons.getValue("cpn_1").status)
    }

    @Test
    fun `reserve holds for the order, release hands it back, and release of another holder is ignored`() {
        val claim = given()
        source.reserve(claim, target(), "ord_1")
        assertEquals(CouponStatus.RESERVED, store.coupons.getValue("cpn_1").status)
        assertEquals("ord_1", store.coupons.getValue("cpn_1").holderRef)

        assertEquals(DiscountRejection.UNAVAILABLE, assertFailsWith<DiscountRejectedException> { source.reserve(claim, target(), "ord_2") }.reason)

        source.release("ord_other")
        assertEquals(CouponStatus.RESERVED, store.coupons.getValue("cpn_1").status)

        source.release("ord_1")
        assertEquals(CouponStatus.AVAILABLE, store.coupons.getValue("cpn_1").status)
        source.reserve(claim, target(), "ord_2")
        assertEquals("ord_2", store.coupons.getValue("cpn_1").holderRef)
    }

    @Test
    fun `release and restore of a coupon past its expiry leave it expired`() {
        val claim = given(c = coupon(expiresAt = NOW.plus(Duration.ofHours(1))))
        source.reserve(claim, target(), "ord_1")
        time.current = NOW.plus(Duration.ofHours(2))
        source.release("ord_1")
        assertEquals(CouponStatus.EXPIRED, store.coupons.getValue("cpn_1").status)

        store.coupons["cpn_1"] = coupon(status = CouponStatus.USED, expiresAt = NOW.plus(Duration.ofHours(1)), holderRef = "ord_9")
        source.restore("ord_9")
        assertEquals(CouponStatus.EXPIRED, store.coupons.getValue("cpn_1").status)
    }

    @Test
    fun `confirm turns the held coupon used and a repeat is already`() {
        val claim = given()
        source.reserve(claim, target(), "ord_1")
        assertEquals(DiscountConfirmOutcome.CONFIRMED, source.confirm("ord_1"))
        assertEquals(CouponStatus.USED, store.coupons.getValue("cpn_1").status)
        assertEquals(DiscountConfirmOutcome.ALREADY, source.confirm("ord_1"))
    }

    @Test
    fun `a late confirm after release takes the coupon back unless another order holds it`() {
        val claim = given()
        source.reserve(claim, target(), "ord_1")
        source.release("ord_1")
        assertEquals(DiscountConfirmOutcome.CONFIRMED, source.confirm("ord_1"), "nobody else took it - the customer already paid")
        assertEquals(CouponStatus.USED, store.coupons.getValue("cpn_1").status)

        source.restore("ord_1")
        source.reserve(claim, target(), "ord_2")
        assertEquals(DiscountConfirmOutcome.CONFLICT, source.confirm("ord_1"), "another order holds it now")
        assertEquals("ord_2", store.coupons.getValue("cpn_1").holderRef)
        assertEquals(CouponStatus.RESERVED, store.coupons.getValue("cpn_1").status)
    }

    @Test
    fun `restore after a full refund makes the coupon available again, only for its holder`() {
        val claim = given()
        source.reserve(claim, target(), "ord_1")
        source.confirm("ord_1")
        source.restore("ord_other")
        assertEquals(CouponStatus.USED, store.coupons.getValue("cpn_1").status)
        source.restore("ord_1")
        assertEquals(CouponStatus.AVAILABLE, store.coupons.getValue("cpn_1").status)
        source.reserve(claim, target(), "ord_2")
    }
}
