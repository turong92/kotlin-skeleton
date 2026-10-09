package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RefundSyncTest {
    private val op = "op_1"

    @Test
    fun `a cancel made in the provider console appears as one refund row`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val cancel = kit.gateway.consoleCancel("pay_1", 1000)

        val synced = kit.refunds.sync(op, order.id)

        val line = kit.store.listRefunds(order.id).single()
        assertEquals(PaymentRefundOrigin.PROVIDER, line.origin)
        assertEquals(PaymentRefundStatus.SUCCEEDED, line.status)
        assertEquals("PROVIDER_CONSOLE", line.reasonCode)
        assertEquals(cancel.providerRefundId, line.providerRefundId)
        assertEquals(1000, line.amount.amount)
        assertNull(line.requestedBy)
        assertNull(line.openKey)
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, synced.status)
        assertEquals(1000, synced.refundedAmount)
        assertNull(synced.reviewReason)
        assertEquals(PaymentEventType.REFUND_SYNCED, kit.store.listEvents(order.id).first { it.refundId == line.id }.type)
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY))
        assertEquals(1, kit.listener.refunded.size, "the app learns about it too")
    }

    @Test
    fun `a cancel made in the provider console twice is still one row`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.consoleCancel("pay_1", 1000)

        kit.refunds.sync(op, order.id)
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY)) // 회수가 끝난 뒤의 맞추기는 회수 작업을 다시 넣지 않는다
        kit.refunds.sync(op, order.id)
        kit.refunds.sync(op, order.id)

        assertEquals(1, kit.store.listRefunds(order.id).size)
        assertEquals(1000, kit.order(order.id).refundedAmount)
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.REFUND_APPLY).size)
        assertEquals(1, kit.listener.refunded.size)
    }

    @Test
    fun `two console cancels become two rows and a full one ends the order as refunded`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.consoleCancel("pay_1", 1000)
        kit.gateway.consoleCancel("pay_1", 2000)

        val synced = kit.refunds.sync(op, order.id)

        assertEquals(2, kit.store.listRefunds(order.id).size)
        assertEquals(PaymentOrderStatus.REFUNDED, synced.status)
        assertEquals(3000, synced.refundedAmount)
    }

    @Test
    fun `sync with nothing to find changes nothing - not the row, not the events`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val events = kit.store.listEvents(order.id)

        val synced = kit.refunds.sync(op, order.id)

        assertEquals(order, synced)
        assertEquals(events, kit.store.listEvents(order.id))
    }

    @Test
    fun `converging the same snapshot twice changes nothing the second time`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.consoleCancel("pay_1", 1000)
        val snapshot = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!
        val context = ConvergeContext(PaymentMode.TEST, ConvergeSource.WEBHOOK)

        val first = kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(order.id)!!, snapshot, context) }
        val second = kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(order.id)!!, snapshot, context) }

        assertEquals(true, first.changed)
        assertEquals(false, second.changed)
        assertEquals(1, kit.store.listRefunds(order.id).size)
    }

    @Test
    fun `a provider whose remaining amount disagrees with the ledger flags the order and a later agreement clears it`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val skewed = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!.copy(remaining = PaymentAmount(1234, "KRW"))
        val context = ConvergeContext(PaymentMode.TEST, ConvergeSource.WEBHOOK)

        kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(order.id)!!, skewed, context) }
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(1, kit.review.flagged.size)

        kit.refunds.sync(op, order.id)
        assertNull(kit.order(order.id).reviewReason)
    }

    @Test
    fun `a snapshot older than a refund change is not judged on its remaining amount`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val snapshot = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!! // 환불 전에 얻은 스냅샷
        val lookedAt = kit.time.now()
        kit.advance(java.time.Duration.ofSeconds(5))
        kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")) // 그 뒤에 환불이 성공했다

        kit.store.inTransaction { tx ->
            kit.convergence.converge(tx, tx.lockOrder(order.id)!!, snapshot, ConvergeContext(PaymentMode.TEST, ConvergeSource.REFUND, snapshotAt = lookedAt))
        }

        assertNull(kit.order(order.id).reviewReason, "a stale snapshot says nothing about the refund that came after it")
    }

    @Test
    fun `sync refuses an order of another mode and says so on the order`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, order.id) }

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
        assertFailsWith<PaymentOrderNotFoundException> { kit.refunds.sync(op, "ord_" + "0".repeat(32)) }
    }
}
