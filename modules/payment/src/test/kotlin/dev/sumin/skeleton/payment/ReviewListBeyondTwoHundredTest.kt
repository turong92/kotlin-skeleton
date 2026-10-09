package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「확인 필요」의 환불 갈래는 안전망이다 — 워커가 죽었거나 회수가 깨진 배포처럼 묶인 줄이 **많을 때** 가장 필요하다.
 * 그래서 줄이 많아도 가장 오래된 것이 빠지지 않고, 필터(`accountId`)와 커서가 읽은 뒤가 아니라 SQL 에서 걸려야 한다.
 */
class ReviewListBeyondTwoHundredTest {
    private val kit = PaymentLedgerKit()
    private val queries = PaymentQueries(kit.store, kit.router, kit.writer, kit.properties)

    private fun order(n: Int, account: String) =
        PaymentTestOrders.order(id = "ord_" + n.toString().padStart(32, '0'), accountId = account, status = PaymentOrderStatus.PAID, now = PaymentTestOrders.T0.plusSeconds(n.toLong()))
            .copy(fulfilledAt = PaymentTestOrders.T0.plusSeconds(n.toLong()), paidAt = PaymentTestOrders.T0.plusSeconds(n.toLong()))

    /** 주문 1~[total] 이 모두 오래 `PENDING` 인 환불 줄을 하나씩 가진다. 가장 오래된 [oldOnes] 개는 `acc_old` 의 것. */
    private fun stuckRefunds(total: Int, oldOnes: Int, status: PaymentRefundStatus = PaymentRefundStatus.PENDING) {
        val long = kit.time.now().minus(Duration.ofDays(1))
        (1..total).forEach { n ->
            val order = order(n, if (n <= oldOnes) "acc_old" else "acc_new")
            kit.store.inTransaction { tx ->
                tx.insertOrderIfAbsent(order)
                tx.lockOrder(order.id)
                val line = PaymentTestOrders.refund(id = "rfd_" + n.toString().padStart(32, '0'), orderId = order.id, status = status, now = order.createdAt)
                tx.insertRefundIfAbsent(if (status == PaymentRefundStatus.PENDING) line.copy(sentAt = long) else line.copy(completedAt = long))
            }
        }
    }

    private fun allReviewIds(accountId: String? = null): List<String> {
        val ids = mutableListOf<String>()
        var cursor: OrderCursor? = null
        do {
            val page = queries.operatorOrders(OperatorOrderQuery(accountId = accountId, review = true, before = cursor, size = 100))
            ids += page.values.map { it.order.id }
            cursor = page.next
        } while (cursor != null)
        return ids
    }

    @Test
    fun `with more than 200 stale pending refunds the oldest order is still in the review list`() {
        stuckRefunds(total = 250, oldOnes = 10)

        val ids = allReviewIds()

        assertEquals(250, ids.size)
        assertEquals(ids.distinct(), ids)
        assertTrue("ord_" + "1".padStart(32, '0') in ids, "the order of the oldest stuck refund must not be dropped")
    }

    @Test
    fun `the account filter is applied before the cut - the stuck refunds of a quiet account are found behind 240 of a loud one`() {
        stuckRefunds(total = 250, oldOnes = 10)

        val ids = allReviewIds("acc_old")

        assertEquals((1..10).map { "ord_" + it.toString().padStart(32, '0') }.reversed(), ids)
    }

    @Test
    fun `unapplied succeeded refunds get the same treatment`() {
        stuckRefunds(total = 250, oldOnes = 10, status = PaymentRefundStatus.SUCCEEDED)

        assertEquals(10, allReviewIds("acc_old").size)
        assertEquals(250, allReviewIds().size)
    }
}
