package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.OrderDiscount
import dev.sumin.skeleton.payment.OrderDiscountStatus
import dev.sumin.skeleton.payment.PaymentSettlement
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.dao.DataAccessException

/** 두 방언에서: 새 두 칸과 할인 줄 표의 왕복, 이 열 이전의 옛 줄, 표의 약속(주문 없는 줄 · 0 이하 금액 · 같은 순번은 못 들어간다). */
class OrderDiscountRowsDbTest {
    private val store get() = PaymentDb.newStore()

    @BeforeTest
    fun clean() = PaymentDb.clean()

    @Test
    fun `an old row without the new columns reads as no original amount and no discount, and a discounted row round-trips`() {
        val now = DbTestDatabase.dialect.instantParam(Instant.parse("2026-10-01T00:00:00Z"))
        PaymentDb.jdbc.update(
            """insert into payment_orders (id, account_id, kind, status, provider, provider_mode, currency, amount, item_type, item_ref, order_name, origin_type, checkout_expires_at, created_at, updated_at)
               values ('ord_old', 'acc_1', 'ONE_TIME', 'PENDING', 'scripted', 'TEST', 'KRW', 3000, 'cheer', 'c', 'Cheer', 'CHECKOUT', :now, :now, :now)""",
            mapOf("now" to now),
        )
        val old = store.findOrder("ord_old")!!
        assertNull(old.originalAmount)
        assertEquals(0, old.discountAmount)
        assertEquals(PaymentSettlement.PROVIDER, old.settlement)

        val s = store
        val order = PaymentTestOrders.order(id = "ord_" + "d".repeat(32), dedupeKey = "dd", amount = dev.sumin.skeleton.payment.PaymentAmount(2100, "KRW")).copy(originalAmount = 3000, discountAmount = 900)
        s.inTransaction { tx ->
            tx.insertOrderIfAbsent(order)
            tx.insertOrderDiscount(OrderDiscount(order.id, 1, "coupon", "cpn_1", "30% off", 900, "KRW", OrderDiscountStatus.RESERVED, PaymentTestOrders.T0, PaymentTestOrders.T0))
        }
        assertEquals(order, s.findOrder(order.id))
        assertEquals(900, s.listOrderDiscounts(listOf(order.id)).single().amount)
    }

    @Test
    fun `the table refuses a line for no order, a zero amount and a repeated sequence number`() {
        val s = store
        val order = PaymentTestOrders.order(id = "ord_" + "e".repeat(32), dedupeKey = "ee")
        s.inTransaction { it.insertOrderIfAbsent(order) }
        val now = DbTestDatabase.dialect.instantParam(PaymentTestOrders.T0)
        fun insert(orderId: String, seq: Int, amount: Long) = PaymentDb.jdbc.update(
            "insert into payment_order_discounts (order_id, seq, kind, source_ref, label, amount, currency, status, created_at, updated_at) values (:o, :s, 'coupon', 'c', 'l', :a, 'KRW', 'RESERVED', :now, :now)",
            mapOf("o" to orderId, "s" to seq, "a" to amount, "now" to now),
        )

        assertFailsWith<DataAccessException> { insert("ord_nobody", 1, 100) }
        assertFailsWith<DataAccessException> { insert(order.id, 1, 0) }   // PostgreSQL 은 DataIntegrityViolation, MySQL 8.4 는 번역되지 않은 check 위반 - 둘 다 집행한다
        insert(order.id, 1, 100)
        assertFailsWith<DataAccessException> { insert(order.id, 1, 100) }
    }
}
