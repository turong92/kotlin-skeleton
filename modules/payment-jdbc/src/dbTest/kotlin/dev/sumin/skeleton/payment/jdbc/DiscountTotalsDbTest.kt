package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.discount.CurrencyAmount
import dev.sumin.skeleton.payment.OrderDiscount
import dev.sumin.skeleton.payment.OrderDiscountStatus
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 두 방언에서: 할인 수단이 깎아 준 금액의 조회는 확정된 줄만 통화별로 합치고(복원 · 쥠 · 풀림은 뺀다), 다른 수단 · 다른 식별은 섞이지 않으며, 식별 목록이 길어도 끊어 묻는다. */
class DiscountTotalsDbTest {
    private val totals = JdbcDiscountTotals(PaymentDb.jdbc)

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun line(seq: Int, kind: String, ref: String, amount: Long, currency: String, status: OrderDiscountStatus, orderId: String) =
        OrderDiscount(orderId, seq, kind, ref, "label", amount, currency, status, PaymentTestOrders.T0, PaymentTestOrders.T0)

    private fun order(key: String, lines: List<(String) -> OrderDiscount>) {
        val order = PaymentTestOrders.order(id = "ord_" + key.padEnd(32, 'a'), dedupeKey = "dk_$key", amount = PaymentAmount(1000, "KRW"))
        PaymentDb.newStore().inTransaction { tx ->
            tx.insertOrderIfAbsent(order)
            lines.forEach { tx.insertOrderDiscount(it(order.id)) }
        }
    }

    @Test
    fun `only confirmed lines of the asked kind and references are summed per currency, in currency order`() {
        order("one", listOf({ id -> line(1, "coupon", "cpn_a", 900, "KRW", OrderDiscountStatus.CONFIRMED, id) }))
        order("two", listOf({ id -> line(1, "coupon", "cpn_b", 600, "KRW", OrderDiscountStatus.CONFIRMED, id) }))
        order("three", listOf({ id -> line(1, "coupon", "cpn_c", 5000, "KRW", OrderDiscountStatus.RESTORED, id) }))
        order("four", listOf({ id -> line(1, "coupon", "cpn_d", 700, "KRW", OrderDiscountStatus.RESERVED, id) }))
        order("five", listOf({ id -> line(1, "coupon", "cpn_e", 800, "KRW", OrderDiscountStatus.RELEASED, id) }))
        order("six", listOf({ id -> line(1, "coupon", "cpn_a", 5, "USD", OrderDiscountStatus.CONFIRMED, id) }))
        order("seven", listOf({ id -> line(1, "points", "cpn_a", 4000, "KRW", OrderDiscountStatus.CONFIRMED, id) }))
        order("eight", listOf({ id -> line(1, "coupon", "cpn_other", 3000, "KRW", OrderDiscountStatus.CONFIRMED, id) }))

        val refs = listOf("cpn_a", "cpn_b", "cpn_c", "cpn_d", "cpn_e", "cpn_a")
        assertEquals(listOf(CurrencyAmount("KRW", 1500), CurrencyAmount("USD", 5)), totals.confirmedTotals("coupon", refs))
        assertEquals(listOf(CurrencyAmount("KRW", 4000)), totals.confirmedTotals("points", refs))
        assertEquals(emptyList(), totals.confirmedTotals("coupon", emptyList()))
        assertEquals(emptyList(), totals.confirmedTotals("coupon", listOf("cpn_none")))
    }

    @Test
    fun `a reference list longer than one chunk is asked in pieces and added up`() {
        order("long", listOf({ id -> line(1, "coupon", "cpn_first", 100, "KRW", OrderDiscountStatus.CONFIRMED, id) }))
        order("long2", listOf({ id -> line(1, "coupon", "cpn_last", 250, "KRW", OrderDiscountStatus.CONFIRMED, id) }))
        val refs = listOf("cpn_first") + List(2500) { "cpn_filler_$it" } + listOf("cpn_last")
        assertEquals(listOf(CurrencyAmount("KRW", 350)), totals.confirmedTotals("coupon", refs))
    }
}
