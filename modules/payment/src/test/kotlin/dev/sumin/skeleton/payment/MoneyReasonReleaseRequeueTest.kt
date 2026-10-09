package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 돈 이유(`REFUND_MISMATCH` · `MODE_MISMATCH`)가 **사람 없이** 풀려도 그 아래에 있던 미지급 · 미회수 일은 다시 돈다(스펙 §15, B4 · B5 확인 리뷰 3회차).
 * 돈 이유가 칸을 차지한 동안 죽은 지급 · 회수는 표시도 이력도 남기지 못하므로, 이유가 풀리는 자리마다 같은 트랜잭션에서 작업을 다시 넣는다.
 * 지급 쪽 표시가 낡은 채 남지 않는 것(지급 · 회수가 다른 길로 성공하면 자기 이유만 푼다)과 `MODE_MISMATCH` 를 함부로 풀지 않는 것도 함께 본다.
 */
class MoneyReasonReleaseRequeueTest {
    private val op = "op_1"

    private fun dead(type: String, subject: String) = PaymentTaskRun(type, subject, 12, 12)

    private fun flag(kit: PaymentLedgerKit, orderId: String, reason: PaymentReviewReason, source: ConvergeSource = ConvergeSource.REFUND) =
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(orderId)!!, reason, ConvergeContext(PaymentMode.TEST, source, PaymentActor.OPERATOR, op)).order }

    private fun webhookArrives(kit: PaymentLedgerKit, orderId: String) {
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-${System.nanoTime()}", orderId = orderId))
        kit.runWebhooks()
    }

    private fun unfulfilled(kit: PaymentLedgerKit): PaymentOrder {
        kit.listener.failPaid(1000)
        val order = kit.paid()
        assertNull(kit.order(order.id).fulfilledAt)
        return kit.order(order.id)
    }

    private fun unapplied(kit: PaymentLedgerKit, order: PaymentOrder, key: String = "k-1"): PaymentRefund {
        kit.listener.failRefunded(1000)
        return kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey(key)).refund
    }

    private fun fulfilTasks(kit: PaymentLedgerKit) = kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).size

    private fun applyTasks(kit: PaymentLedgerKit) = kit.tasks.all(PaymentTaskTypes.REFUND_APPLY).size

    // ---- 웹훅이 REFUND_MISMATCH 를 푸는 길 ----

    @Test
    fun `fulfil died, then the money reason covered it, then a webhook cleared it - the fulfil job comes back`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)
        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, order.id))
        flag(kit, order.id, PaymentReviewReason.REFUND_MISMATCH)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        val before = fulfilTasks(kit)

        webhookArrives(kit, order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(before + 1, fulfilTasks(kit), "the mark is gone, so the unpaid work must be queued again")
        kit.listener.failPaid(0)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        assertNotNull(kit.order(order.id).fulfilledAt)
    }

    @Test
    fun `the money reason first, then the fulfil job dies under it, then a webhook clears it - the fulfil job comes back`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)
        flag(kit, order.id, PaymentReviewReason.REFUND_MISMATCH)
        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, order.id))
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        val before = fulfilTasks(kit)

        webhookArrives(kit, order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(before + 1, fulfilTasks(kit))
    }

    @Test
    fun `apply died, then the money reason covered it, then a webhook cleared it - the apply job comes back`() {
        val kit = PaymentLedgerKit()
        val refund = unapplied(kit, kit.paid())
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_APPLY, refund.id))
        flag(kit, refund.orderId, PaymentReviewReason.REFUND_MISMATCH)
        val before = applyTasks(kit)

        webhookArrives(kit, refund.orderId)

        assertNull(kit.order(refund.orderId).reviewReason)
        assertEquals(before + 1, applyTasks(kit), "the unapplied refund must be queued again")
        kit.listener.failRefunded(0)
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY))
        assertNotNull(kit.store.findRefund(refund.id)!!.appliedAt)
    }

    @Test
    fun `the money reason first, then the apply job dies under it, then a webhook clears it - the apply job comes back`() {
        val kit = PaymentLedgerKit()
        val refund = unapplied(kit, kit.paid())
        flag(kit, refund.orderId, PaymentReviewReason.REFUND_MISMATCH)
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_APPLY, refund.id))
        val before = applyTasks(kit)

        webhookArrives(kit, refund.orderId)

        assertNull(kit.order(refund.orderId).reviewReason)
        assertEquals(before + 1, applyTasks(kit))
    }

    @Test
    fun `a webhook that clears the mark of an order with nothing left to do queues nothing`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.REFUND_MISMATCH)
        val fulfil = fulfilTasks(kit)
        val apply = applyTasks(kit)

        webhookArrives(kit, order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(fulfil, fulfilTasks(kit))
        assertEquals(apply, applyTasks(kit))
    }

    @Test
    fun `a person's sync that clears the mark queues the fulfil job exactly once`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)
        flag(kit, order.id, PaymentReviewReason.REFUND_MISMATCH)
        val before = fulfilTasks(kit)

        kit.refunds.sync(op, order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(before + 1, fulfilTasks(kit), "sync requeues on its own - the convergence it runs must not stack a second job")
    }

    // ---- MODE_MISMATCH ----

    @Test
    fun `a sync that releases a mode mismatch of an unfulfilled paid order queues the fulfil job once`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)
        flag(kit, order.id, PaymentReviewReason.MODE_MISMATCH)
        val before = fulfilTasks(kit)

        kit.refunds.sync(op, order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(before + 1, fulfilTasks(kit))
    }

    @Test
    fun `a sync whose lookup finds no payment does not release a mode mismatch`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        flag(kit, order.id, PaymentReviewReason.MODE_MISMATCH)

        assertFailsWith<PaymentProviderPaymentNotFoundException> { kit.refunds.sync(op, order.id) }

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason, "nobody knows the state of this order yet - a person keeps watching")
    }

    @Test
    fun `a sync that finds the payment still in progress does not release a mode mismatch`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        flag(kit, order.id, PaymentReviewReason.MODE_MISMATCH)

        kit.refunds.sync(op, order.id)

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(0, kit.store.listEvents(order.id).count { it.providerCode == "MODE_MISMATCH_CLEARED" })
    }

    // ---- 지급 쪽 표시가 낡은 채 남지 않는다 ----

    @Test
    fun `a fulfilment that succeeds some other way clears its own mark`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)
        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, order.id))
        assertEquals(PaymentReviewReason.FULFILMENT_UNRESOLVED, kit.order(order.id).reviewReason)
        kit.listener.failPaid(0)

        assertEquals(true, kit.fulfillment.fulfil(order.id))

        assertNull(kit.order(order.id).reviewReason)
        assertEquals("FULFILMENT_UNRESOLVED_CLEARED", kit.store.listEvents(order.id).last().providerCode)
    }

    @Test
    fun `a fulfilment that succeeds leaves a money reason alone`() {
        val kit = PaymentLedgerKit()
        val order = unfulfilled(kit)
        flag(kit, order.id, PaymentReviewReason.AMOUNT_MISMATCH)
        kit.listener.failPaid(0)

        kit.fulfillment.fulfil(order.id)

        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
        assertNotNull(kit.order(order.id).fulfilledAt)
    }

    @Test
    fun `an apply that succeeds clears its mark only when no succeeded refund is left unapplied`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val first = unapplied(kit, order, "k-1")
        val second = unapplied(kit, order, "k-2")
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_APPLY, first.id))
        assertEquals(PaymentReviewReason.REFUND_APPLY_UNRESOLVED, kit.order(first.orderId).reviewReason)
        kit.listener.failRefunded(0)

        kit.applier.apply(first.id)
        assertEquals(PaymentReviewReason.REFUND_APPLY_UNRESOLVED, kit.order(first.orderId).reviewReason, "the second refund is still unapplied")

        kit.applier.apply(second.id)
        assertNull(kit.order(first.orderId).reviewReason)
        assertEquals("REFUND_APPLY_UNRESOLVED_CLEARED", kit.store.listEvents(first.orderId).last().providerCode)
    }

    @Test
    fun `an apply that succeeds leaves a money reason alone`() {
        val kit = PaymentLedgerKit()
        val refund = unapplied(kit, kit.paid())
        flag(kit, refund.orderId, PaymentReviewReason.AMOUNT_MISMATCH)
        kit.listener.failRefunded(0)

        kit.applier.apply(refund.id)

        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(refund.orderId).reviewReason)
    }
}
