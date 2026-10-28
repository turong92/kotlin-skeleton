package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.discount.CurrencyAmount
import dev.sumin.skeleton.common.discount.DiscountTotals
import kotlin.test.Test
import kotlin.test.assertEquals

/** 현황의 할인 합계 - 금액은 결제 장부가 알고, 쿠폰은 쓰인 쿠폰 id 를 덩어리로 끊어 물을 뿐이다 */
class CouponReportsTest {
    private val store = FakeCouponStore()
    private val time = MutableTime()

    private fun usedCoupons(count: Int) {
        store.definitions["cpd_1"] = definition()
        repeat(count) { store.coupons["cpn_%05d".format(it)] = coupon(id = "cpn_%05d".format(it), status = CouponStatus.USED) }
        store.coupons["cpn_open"] = coupon(id = "cpn_open", status = CouponStatus.AVAILABLE)
    }

    @Test
    fun `without a ledger the totals are an empty list, not an error`() {
        usedCoupons(3)
        val stats = CouponReports(store, time).stats("cpd_1")
        assertEquals(emptyList(), stats.discountTotals)
        assertEquals(3, stats.used)
    }

    @Test
    fun `used coupon ids are asked in chunks of at most 1000 with the coupon kind and the currencies are added up`() {
        usedCoupons(2500)
        val asked = mutableListOf<Pair<String, Int>>()
        val ledger = DiscountTotals { kind, refs ->
            asked += kind to refs.size
            listOf(CurrencyAmount("KRW", refs.size.toLong()), CurrencyAmount("USD", 1))
        }
        val stats = CouponReports(store, time) { ledger }.stats("cpd_1")

        assertEquals(listOf("coupon" to 1000, "coupon" to 1000, "coupon" to 500), asked, "the available coupon is not asked about")
        assertEquals(listOf(CouponMoney("KRW", 2500), CouponMoney("USD", 3)), stats.discountTotals)
    }

    @Test
    fun `an exact multiple of the chunk asks the ledger once, the empty probe only touches the store`() {
        usedCoupons(1000)
        var calls = 0
        CouponReports(store, time) { DiscountTotals { _, _ -> calls++; emptyList() } }.stats("cpd_1")
        assertEquals(1, calls)
    }
}
