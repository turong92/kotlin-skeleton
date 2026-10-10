package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 지급 · 회수 작업이 죽으면 「확인 필요」에 오른다(스펙 §15) — `FULFILMENT_UNRESOLVED`(돈은 받았는데 지급이 안 됐다) · `REFUND_APPLY_UNRESOLVED`(돈은 돌려줬는데 회수가 안 됐다).
 * 돈 쪽 이유가 이미 있으면 덮지 않는다. 운영자의 「결제사와 맞추기」는 작업을 한 번만 다시 넣고 이 두 이유를 푼다.
 */
class SupplyDeadJobTest {
    private val op = "op_1"

    private fun dead(type: String, subject: String) = PaymentTaskRun(type, subject, 12, 12)

    private fun unfulfilled(kit: PaymentLedgerKit): PaymentOrder {
        kit.listener.failPaid(1000)
        val order = kit.paid()
        assertNull(kit.order(order.id).fulfilledAt)
        return kit.order(order.id)
    }

    private fun unapplied(kit: PaymentLedgerKit): PaymentRefund {
        val order = kit.paid()
        kit.listener.failRefunded(1000)
        val refund = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        assertEquals(PaymentRefundStatus.SUCCEEDED, refund.status)
        assertNull(kit.store.findRefund(refund.id)!!.appliedAt)
        return refund
    }

    private fun flaggedEvents(kit: PaymentLedgerKit, orderId: String) = kit.store.listEvents(orderId).count { it.type == PaymentEventType.REVIEW_FLAGGED }

    private fun fulfilTasks(kit: PaymentLedgerKit) = kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).size

    private fun applyTasks(kit: PaymentLedgerKit) = kit.tasks.all(PaymentTaskTypes.REFUND_APPLY).size

    @Test
    fun `a dead fulfil job marks an unfulfilled paid order - once, with the alert`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)

        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, order.id))
        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, order.id))

        assertEquals(PaymentReviewReason.FULFILMENT_UNRESOLVED, kit.order(order.id).reviewReason)
        assertEquals(listOf(order.id to PaymentReviewReason.FULFILMENT_UNRESOLVED), kit.review.flagged.toList())
        assertEquals(1, flaggedEvents(kit, order.id))
    }

    @Test
    fun `a dead apply job marks the order while the succeeded refund is not applied - once`() {
        val kit = PaymentLedgerKit()
        val refund = unapplied(kit)

        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_APPLY, refund.id))
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_APPLY, refund.id))

        assertEquals(PaymentReviewReason.REFUND_APPLY_UNRESOLVED, kit.order(refund.orderId).reviewReason)
        assertEquals(listOf(refund.orderId to PaymentReviewReason.REFUND_APPLY_UNRESOLVED), kit.review.flagged.toList())
        assertEquals(1, flaggedEvents(kit, refund.orderId))
    }

    @Test
    fun `sync requeues the fulfil job of an unfulfilled paid order once and clears the mark - a second death marks again`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)
        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, order.id))
        val before = fulfilTasks(kit)

        kit.refunds.sync(op, order.id)

        assertEquals(before + 1, fulfilTasks(kit), "one new job per sync")
        assertNull(kit.order(order.id).reviewReason)
        assertEquals("FULFILMENT_UNRESOLVED_CLEARED", kit.store.listEvents(order.id).last().providerCode)

        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, order.id))
        assertEquals(PaymentReviewReason.FULFILMENT_UNRESOLVED, kit.order(order.id).reviewReason)
    }

    @Test
    fun `sync requeues the apply job of an unapplied succeeded refund once and clears the mark`() {
        val kit = PaymentLedgerKit()
        val refund = unapplied(kit)
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_APPLY, refund.id))
        val before = applyTasks(kit)

        kit.refunds.sync(op, refund.orderId)

        assertEquals(before + 1, applyTasks(kit))
        assertNull(kit.order(refund.orderId).reviewReason)
        assertEquals("REFUND_APPLY_UNRESOLVED_CLEARED", kit.store.listEvents(refund.orderId).last().providerCode)
        kit.listener.failRefunded(0)
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY))
        assertNotNull(kit.store.findRefund(refund.id)!!.appliedAt)
    }
}
