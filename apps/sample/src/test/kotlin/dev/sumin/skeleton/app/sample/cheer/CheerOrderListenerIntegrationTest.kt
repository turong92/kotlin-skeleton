package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.app.sample.orders.SampleOrderListener
import dev.sumin.skeleton.payment.PaymentOrderListener
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired

/**
 * 장부의 두 훅을 이 앱의 리스너로 직접 부른다(진짜 PostgreSQL) — 훅이 지켜야 할 약속: `onPaid` 는 멱등, `onRefunded` 는 일부 환불에서 `PAID` 를 지키고 금액만 올리며 전액에서
 * `REFUNDED` + 환불 시각, **기록이 없는 주문(지급 전 전액 환불)에는 아무것도 하지 않고**, 두 번 불려도 같고, 지워진 계정의 주문은 기록을 되살리지 않는다.
 * HTTP 여정은 [CheerJourneyIntegrationTest] 가 본다.
 */
class CheerOrderListenerIntegrationTest : CheerIntegrationBase() {
    @Autowired lateinit var listener: PaymentOrderListener

    private val paidAt: Instant = Instant.parse("2026-10-08T01:02:03Z")
    private fun paidOrder(id: String = "ord_" + "b".repeat(32), account: String = "acc_listener") =
        PaymentTestOrders.order(id = id, accountId = account, status = PaymentOrderStatus.PAID, amount = PaymentAmount(3000, "KRW")).copy(paidAt = paidAt, paidAmount = 3000)

    private fun row(orderId: String) = jdbc.sql("select status, refunded_amount, refunded_at, created_at, account_id, amount from cheers where order_id = :o").param("o", orderId)
        .query { rs, _ -> listOf(rs.getString("status"), rs.getLong("refunded_amount"), rs.getTimestamp("refunded_at")?.toInstant(), rs.getTimestamp("created_at").toInstant(), rs.getString("account_id"), rs.getLong("amount")) }.list()

    @Test
    fun `the listener on the context is this app's, and onPaid writes one line - twice is still one`() {
        assertEquals(SampleOrderListener::class, listener::class)
        val order = paidOrder()
        listener.onPaid(order)
        listener.onPaid(order)
        val rows = row(order.id)
        assertEquals(1, rows.size)
        assertEquals("PAID", rows.single()[0])
        assertEquals(0L, rows.single()[1])
        assertNull(rows.single()[2])
        assertEquals(paidAt, rows.single()[3], "the line carries the time the money arrived")
        assertEquals(listOf("acc_listener", 3000L), rows.single().drop(4))
    }

    @Test
    fun `a refund of part keeps PAID and raises the amount - the rest closes it, and repeating or an older call changes nothing`() {
        val order = paidOrder()
        listener.onPaid(order)
        val part = order.copy(status = PaymentOrderStatus.PARTIALLY_REFUNDED, refundedAmount = 1000)
        val refund1 = PaymentTestOrders.refund(orderId = order.id, status = PaymentRefundStatus.SUCCEEDED, amount = PaymentAmount(1000, "KRW"))
        listener.onRefunded(part, refund1)
        listener.onRefunded(part, refund1)
        assertEquals(listOf("PAID", 1000L, null), row(order.id).single().take(3))

        val done = Instant.parse("2026-10-08T05:06:07Z")
        val full = order.copy(status = PaymentOrderStatus.REFUNDED, refundedAmount = 3000)
        val refund2 = PaymentTestOrders.refund(id = "rfd_" + "b".repeat(32), orderId = order.id, status = PaymentRefundStatus.SUCCEEDED, amount = PaymentAmount(2000, "KRW")).copy(completedAt = done)
        listener.onRefunded(full, refund2)
        assertEquals(listOf("REFUNDED", 3000L, done), row(order.id).single().take(3))

        // the same call again, and a late call that still carries the earlier total: nothing moves back
        listener.onRefunded(full, refund2.copy(completedAt = done.plusSeconds(60)))
        listener.onRefunded(part, refund1)
        assertEquals(listOf("REFUNDED", 3000L, done), row(order.id).single().take(3))
    }

    @Test
    fun `a refund of an order that was never fulfilled leaves no line behind and does not fail`() {
        val unpaid = paidOrder(id = "ord_" + "c".repeat(32))
        val refunded = unpaid.copy(status = PaymentOrderStatus.REFUNDED, refundedAmount = 3000, fulfilledAt = null)
        listener.onRefunded(refunded, PaymentTestOrders.refund(orderId = unpaid.id, status = PaymentRefundStatus.SUCCEEDED, amount = PaymentAmount(3000, "KRW")))
        assertEquals(0, row(unpaid.id).size)
        assertEquals(0L, jdbc.sql("select count(*) from cheers").query(Long::class.java).single())
    }

    @Test
    fun `an order whose account is already erased does not bring the line back`() {
        val late = paidOrder(id = "ord_" + "d".repeat(32)).copy(accountErasedAt = Instant.parse("2026-10-08T03:00:00Z"))
        listener.onPaid(late)
        assertEquals(0, row(late.id).size)
    }
}
