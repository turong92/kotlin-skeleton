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
    fun `a subscription coupon counts the first order's line and every paid renewal's discount, not a refunded renewal or another kind of order`() {
        val sub = PaymentTestOrders.subscription(id = "sub_" + "t".repeat(32))
        val now = PaymentTestOrders.T0
        PaymentDb.newStore().inTransaction { tx ->
            tx.insertSubscriptionIfAbsent(sub)
            tx.insertSubscriptionDiscount(
                dev.sumin.skeleton.payment.SubscriptionDiscount(
                    sub.id, "coupon", "cpn_sub", "1 free then half",
                    listOf(
                        dev.sumin.skeleton.common.discount.DiscountStage(dev.sumin.skeleton.common.discount.DiscountStageType.FREE, 0, null, 1),
                        dev.sumin.skeleton.common.discount.DiscountStage(dev.sumin.skeleton.common.discount.DiscountStageType.PERCENT, 50, null, 3),
                    ),
                    2, dev.sumin.skeleton.payment.SubscriptionDiscountStatus.ACTIVE, now, now,
                ),
            )
        }
        fun subOrder(key: String, kind: dev.sumin.skeleton.payment.PaymentOrderKind, status: dev.sumin.skeleton.payment.PaymentOrderStatus, discount: Long) =
            PaymentTestOrders.order(id = "ord_" + key.padEnd(32, 'b'), dedupeKey = "dk_$key", status = status, amount = PaymentAmount(10_000 - discount, "KRW"))
                .copy(kind = kind, originType = dev.sumin.skeleton.payment.PaymentOriginType.SUBSCRIPTION, originRef = sub.id, originalAmount = 10_000, discountAmount = discount)
        val initial = subOrder("sinit", dev.sumin.skeleton.payment.PaymentOrderKind.SUBSCRIPTION_INITIAL, dev.sumin.skeleton.payment.PaymentOrderStatus.PAID, 10_000)
        val renewals = listOf(
            subOrder("sren1", dev.sumin.skeleton.payment.PaymentOrderKind.SUBSCRIPTION_RENEWAL, dev.sumin.skeleton.payment.PaymentOrderStatus.PAID, 5_000),
            subOrder("sren2", dev.sumin.skeleton.payment.PaymentOrderKind.SUBSCRIPTION_RENEWAL, dev.sumin.skeleton.payment.PaymentOrderStatus.PARTIALLY_REFUNDED, 5_000),
            subOrder("sren3", dev.sumin.skeleton.payment.PaymentOrderKind.SUBSCRIPTION_RENEWAL, dev.sumin.skeleton.payment.PaymentOrderStatus.REFUNDED, 5_000),
            subOrder("sren4", dev.sumin.skeleton.payment.PaymentOrderKind.SUBSCRIPTION_RENEWAL, dev.sumin.skeleton.payment.PaymentOrderStatus.FAILED, 5_000),
            subOrder("sren5", dev.sumin.skeleton.payment.PaymentOrderKind.SUBSCRIPTION_RENEWAL, dev.sumin.skeleton.payment.PaymentOrderStatus.PAID, 0),
        )
        PaymentDb.newStore().inTransaction { tx ->
            tx.insertOrderIfAbsent(initial)
            tx.insertOrderDiscount(line(1, "coupon", "cpn_sub", 10_000, "KRW", OrderDiscountStatus.CONFIRMED, initial.id))
        }
        renewals.forEach { order -> PaymentDb.newStore().inTransaction { tx -> tx.insertOrderIfAbsent(order) } }   // 한 트랜잭션에 주문 하나
        // 다른 구독의 갱신 · 다른 수단의 같은 식별은 섞이지 않는다
        assertEquals(listOf(CurrencyAmount("KRW", 20_000)), totals.confirmedTotals("coupon", listOf("cpn_sub")), "10,000 (first order, 1 free month) + 5,000 + 5,000 (paid renewals)")
        assertEquals(emptyList(), totals.confirmedTotals("points", listOf("cpn_sub")))
        assertEquals(emptyList(), totals.confirmedTotals("coupon", listOf("cpn_other")))
    }

    @Test
    fun `a reference list longer than one chunk is asked in pieces and added up`() {
        order("long", listOf({ id -> line(1, "coupon", "cpn_first", 100, "KRW", OrderDiscountStatus.CONFIRMED, id) }))
        order("long2", listOf({ id -> line(1, "coupon", "cpn_last", 250, "KRW", OrderDiscountStatus.CONFIRMED, id) }))
        val refs = listOf("cpn_first") + List(2500) { "cpn_filler_$it" } + listOf("cpn_last")
        assertEquals(listOf(CurrencyAmount("KRW", 350)), totals.confirmedTotals("coupon", refs))
    }
}
