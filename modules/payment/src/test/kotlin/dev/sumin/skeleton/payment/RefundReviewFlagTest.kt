package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `REFUND_MISMATCH` 표시는 **풀리지 않은 `PENDING` 환불 줄을 화면에서 지우지 않는다** — 사람이 누른 「결제사와 맞추기」(`SYNC`)이거나 그 주문에 `PENDING` 줄이 없을 때만 풀리고,
 * 결제사가 요청과 다른 금액을 취소한(`RESULT_MISMATCH`) 표시는 `SYNC` 로만 풀린다. 그리고 표시가 있는 주문에는 환불을 보내지 않는다.
 */
class RefundReviewFlagTest {
    private val op = "op_1"
    private val settleDelay = Duration.ofMinutes(2)

    private fun webhookContext() = ConvergeContext(PaymentMode.TEST, ConvergeSource.WEBHOOK)

    private fun convergeNow(kit: PaymentLedgerKit, orderId: String, context: ConvergeContext = webhookContext()) {
        val snapshot = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!
        kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(orderId)!!, snapshot, context) }
    }

    // --- I-3 ---

    @Test
    fun `a flag from a dead settle job survives a later automatic reconcile whose numbers agree - the pending line is still unresolved`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.TimeoutNotApplied)
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        kit.refundReconcile.onDead(PaymentTaskRun(PaymentTaskTypes.REFUND_RECONCILE, line.id, 12, 12))
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)

        convergeNow(kit, order.id, webhookContext())
        convergeNow(kit, order.id, ConvergeContext(PaymentMode.TEST, ConvergeSource.REFUND))
        convergeNow(kit, order.id, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE))

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason, "the order stays in the needs-attention list")
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
    }

    @Test
    fun `the person pressing sync clears that flag and settles the line`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.TimeoutNotApplied)
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        kit.refundReconcile.onDead(PaymentTaskRun(PaymentTaskTypes.REFUND_RECONCILE, line.id, 12, 12))
        kit.advance(settleDelay)

        kit.refunds.sync(op, order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(PaymentRefundStatus.FAILED, kit.store.findRefund(line.id)!!.status)
    }

    @Test
    fun `a flag with no pending line left is cleared by an automatic reconcile as before`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val skewed = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!.copy(remaining = PaymentAmount(1234, "KRW"))
        kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(order.id)!!, skewed, webhookContext()) }
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)

        convergeNow(kit, order.id)

        assertNull(kit.order(order.id).reviewReason)
    }

    @Test
    fun `a different amount cancelled by the provider is not turned into a console cancel and cleared by itself - only sync resolves it`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveOtherAmount(PaymentAmount(500, "KRW")))
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        kit.advance(settleDelay)

        kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE)) // the settle job: it absorbs the 500 but must not close our line or clear the flag
        convergeNow(kit, order.id)
        convergeNow(kit, order.id, ConvergeContext(PaymentMode.TEST, ConvergeSource.REFUND))

        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status, "left open for a person")
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(State.DEAD, kit.tasks.all(PaymentTaskTypes.REFUND_RECONCILE).single().state, "the job gives up at once instead of spinning")

        kit.refunds.sync(op, order.id)

        assertEquals(PaymentRefundStatus.FAILED, kit.store.findRefund(line.id)!!.status, "sync closes our line - the 1000 never went out")
        assertNull(kit.order(order.id).reviewReason)
        assertEquals(500, kit.order(order.id).refundedAmount)
    }

    // --- m5 ---

    @Test
    fun `an order that already carries a refund mismatch gets no refund until someone presses sync - not directly and not by approval`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val request = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("req")).refund
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(order.id)!!, PaymentReviewReason.REFUND_MISMATCH, webhookContext()) }
        val lookups = kit.gateway.lookupCalls.size

        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("k-1")) }
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.approve(op, request.id) }

        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(request.id)!!.status, "the request is as it was")
        assertEquals(lookups, kit.gateway.lookupCalls.size, "it did not even ask the provider")
        kit.refunds.sync(op, order.id)
        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.refunds.approve(op, request.id).refund.status)
    }

    @Test
    fun `a pre-refund check that has just flagged a mismatch sends nothing - the numbers disagreed`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val request = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("req")).refund
        kit.advance(Duration.ofSeconds(1)) // 요청 줄보다 뒤에 얻은 스냅샷이어야 낡은 것으로 건너뛰지 않는다
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(order.id)!!.copy(refundedAmount = 500)) } // 장부만 500 이 환불됐다고 믿는다

        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.approve(op, request.id) }

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(request.id)!!.status)
        val direct = PaymentLedgerKit()
        val other = direct.paid()
        direct.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(other.id)!!.copy(refundedAmount = 500)) }
        assertFailsWith<PaymentRefundNotAllowedException> { direct.refunds.refund(op, other.id, "OPERATOR", 500, IdempotencyKey("k-1")) }
        assertEquals(0, direct.gateway.refundCalls.size)
    }

    // --- m9 ---

    @Test
    fun `a console cancel of everything that is left closes the open request as withdrawn with the reason order-refunded`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val request = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("req")).refund
        kit.gateway.consoleCancel("pay_1", 3000)

        kit.refunds.sync(op, order.id)

        val closed = kit.store.findRefund(request.id)!!
        assertEquals(PaymentRefundStatus.WITHDRAWN, closed.status)
        assertNull(closed.openKey)
        assertEquals("ORDER_REFUNDED", closed.failureCode)
        val event = kit.store.listEvents(order.id).single { it.refundId == request.id && it.type == PaymentEventType.REFUND_WITHDRAWN }
        assertEquals(PaymentActor.SYSTEM, event.actor)
        assertEquals("ORDER_REFUNDED", event.providerCode)
        assertEquals("REQUESTED", event.fromStatus)
        assertEquals("WITHDRAWN", event.toStatus)
        val view = kit.refunds.get("acc_1", order.id)
        assertEquals(PaymentOrderStatus.REFUNDED, view.order.status)
        assertNull(view.summary.openRefundStatus, "a refunded order does not show an open request")
        assertTrue(!view.summary.refundPending)
        assertNotNull(kit.store.findRefund(request.id)!!.completedAt)
    }

    @Test
    fun `a partial console cancel leaves the request open - only a fully refunded order has nothing left to ask for`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val request = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("req")).refund
        kit.gateway.consoleCancel("pay_1", 1000)

        kit.refunds.sync(op, order.id)

        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(request.id)!!.status)
    }
}
