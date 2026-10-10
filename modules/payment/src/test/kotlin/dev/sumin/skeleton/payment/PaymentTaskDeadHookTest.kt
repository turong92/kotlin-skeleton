package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 죽은 작업의 고리([PaymentTaskHandler.onDead] · [PaymentTaskRunner.onDead]) — 큐가 작업을 DEAD 로 만드는 모든 길(마지막 시도 · 영구 실패 · **처리기를 거치지 않는 멈춤 복구**)이
 * 이 고리를 부른다. 처리기가 마지막 시도에서 표시를 못 찍은 채 죽어도 줄이 「확인 필요」 목록에 오르는지를 여기서(메모리 장부) 본다. 진짜 큐 · DB 로는 `payment-jdbc` 의 dbTest 가 본다.
 */
class PaymentTaskDeadHookTest {
    private fun dead(type: String, subject: String, attempt: Int = 12, max: Int = 12) = PaymentTaskRun(type, subject, attempt, max)

    private fun confirming(kit: PaymentLedgerKit): PaymentOrder {
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        val result = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, result.status)
        return result
    }

    private fun pendingRefund(kit: PaymentLedgerKit): PaymentRefund {
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.TimeoutApplied)
        val outcome = kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        return outcome.refund
    }

    private fun flaggedEvents(kit: PaymentLedgerKit, orderId: String) = kit.store.listEvents(orderId).count { it.type == PaymentEventType.REVIEW_FLAGGED }

    // ---- 조회 작업 ----

    @Test
    fun `a dead reconcile job flags a still confirming order that has no mark - once, with the alert`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)

        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_RECONCILE, order.id))

        val flagged = kit.order(order.id)
        assertEquals(PaymentOrderStatus.CONFIRMING, flagged.status)
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, flagged.reviewReason)
        assertEquals(listOf(order.id to PaymentReviewReason.CONFIRM_UNRESOLVED), kit.review.flagged.toList())
        assertEquals(1, flaggedEvents(kit, order.id))
    }

    @Test
    fun `a dead reconcile job keeps the reason that was marked first`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)
        kit.store.inTransaction { tx ->
            kit.convergence.flagReview(tx, tx.lockOrder(order.id)!!, PaymentReviewReason.MODE_MISMATCH, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE))
        }

        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_RECONCILE, order.id))

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(1, kit.review.flagged.size)
    }

    @Test
    fun `a dead reconcile job leaves an order that finished by another way alone`() {
        val kit = PaymentLedgerKit()
        val paid = kit.paid()

        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_RECONCILE, paid.id))
        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_RECONCILE, "ord_missing"))

        assertNull(kit.order(paid.id).reviewReason)
        assertEquals(PaymentOrderStatus.PAID, kit.order(paid.id).status)
        assertEquals(emptyList(), kit.review.flagged.toList())
    }

    // ---- 환불 정산 작업 ----

    @Test
    fun `a dead refund settle job marks the order REFUND_MISMATCH while the refund is still pending - once`() {
        val kit = PaymentLedgerKit()
        val refund = pendingRefund(kit)

        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_RECONCILE, refund.id))
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_RECONCILE, refund.id))

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(refund.orderId).reviewReason)
        assertEquals(listOf(refund.orderId to PaymentReviewReason.REFUND_MISMATCH), kit.review.flagged.toList())
        assertEquals(1, flaggedEvents(kit, refund.orderId))
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(refund.id)!!.status, "the line is left as it is - a person decides")
    }

    @Test
    fun `a dead refund settle job leaves a refund that was settled by another way alone`() {
        val kit = PaymentLedgerKit()
        val refund = pendingRefund(kit)
        kit.advance(java.time.Duration.ofMinutes(2))
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE))
        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.store.findRefund(refund.id)!!.status)

        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_RECONCILE, refund.id))
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_RECONCILE, "rfd_missing"))

        assertNull(kit.order(refund.orderId).reviewReason)
        assertEquals(emptyList(), kit.review.flagged.toList())
    }

    @Test
    fun `a dead refund settle job reads the line again under the lock - a refund settled after the first read is not flagged`() {
        val kit = PaymentLedgerKit()
        val pending = pendingRefund(kit)
        kit.advance(java.time.Duration.ofMinutes(2))
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE))
        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.store.findRefund(pending.id)!!.status)
        // 첫 번째 읽기는 낡은 PENDING 을 본다 - 락 안의 다시 읽기가 SUCCEEDED 를 봐야 한다
        val staleFirstRead = object : PaymentLedgerStore by kit.store {
            override fun findRefund(id: String): PaymentRefund? = if (id == pending.id) pending else kit.store.findRefund(id)
        }
        val handler = RefundReconcileTaskHandler(staleFirstRead, kit.router, kit.convergence, kit.writer, kit.properties)

        handler.onDead(dead(PaymentTaskTypes.REFUND_RECONCILE, pending.id))

        assertNull(kit.order(pending.orderId).reviewReason)
        assertEquals(emptyList(), kit.review.flagged.toList())
    }

    // ---- 표시할 것이 없는 작업 · 러너 ----

    @Test
    fun `dead expiry, fulfilment and refund apply jobs mark nothing when nothing is outstanding`() {
        val kit = PaymentLedgerKit()
        val pending = kit.create(key = "k-pending").order
        val paid = kit.paid(key = "k-paid", paymentKey = "pay_paid")

        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_EXPIRE, pending.id))
        kit.runner.onDead(dead(PaymentTaskTypes.ORDER_FULFIL, paid.id))
        kit.runner.onDead(dead(PaymentTaskTypes.REFUND_APPLY, "rfd_missing"))

        assertNull(kit.order(pending.id).reviewReason)
        assertNull(kit.order(paid.id).reviewReason)
        assertEquals(emptyList(), kit.review.flagged.toList())
    }

}
