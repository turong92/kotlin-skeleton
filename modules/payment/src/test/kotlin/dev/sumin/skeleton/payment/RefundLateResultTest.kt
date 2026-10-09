package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 결제사의 **환불됨 응답이 도착했는데 그 사이 줄이 이미 닫혔다**(다른 길이 먼저 `NOT_APPLIED` 로 닫았거나, 같은 금액의 콘솔 취소를 이 줄이 가져갔다).
 * 응답이 말하는 취소는 진짜로 나간 돈이다 — 버리지 않고 표시 · 이력을 남기고 결제사의 취소 목록에서 장부로 맞춘다.
 */
class RefundLateResultTest {
    private val op = "op_1"

    /** 환불 호출이 결제사에 가 있는 동안 [during] 이 장부를 바꾼다. */
    private fun withDuring(kit: PaymentLedgerKit, orderId: String, during: () -> Unit): RefundOutcome {
        kit.gateway.duringRefund = { kit.gateway.duringRefund = null; during() }
        return kit.refunds.refund(op, orderId, "OPERATOR", 1000, IdempotencyKey("k-1"))
    }

    @Test
    fun `a success that arrives after the line was closed as not-applied is kept - the cancel is booked, the order is flagged and the event trail says so`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        val outcome = withDuring(kit, order.id) {
            val line = kit.store.listRefunds(order.id).single()
            kit.store.inTransaction { tx ->
                tx.lockOrder(order.id)
                kit.writer.transitionRefund(tx, tx.lockRefund(line.id)!!, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, PaymentActor.SYSTEM, null, RefundSettlement.NOT_APPLIED) {
                    it.copy(failureCode = RefundSettlement.NOT_APPLIED, completedAt = kit.time.now())
                }
            }
        }

        assertEquals(PaymentRefundStatus.FAILED, outcome.refund.status, "the closed line is not reopened")
        val lines = kit.store.listRefunds(order.id)
        val provider = lines.single { it.origin == PaymentRefundOrigin.PROVIDER }
        assertEquals("txn_1", provider.providerRefundId, "the cancel that really happened is in the ledger")
        assertEquals(1000, kit.order(order.id).refundedAmount)
        assertEquals(kit.gateway.payment("pay_1")!!.refundedTotal, kit.order(order.id).refundedAmount)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(listOf(order.id to PaymentReviewReason.REFUND_MISMATCH), kit.review.flagged)
        val trail = kit.store.listEvents(order.id).filter { it.refundId == outcome.refund.id }.map { it.providerCode }
        assertTrue("LATE_RESULT" in trail, "$trail")
    }

    @Test
    fun `a success that arrives after a console cancel of the same amount took the line is not dropped - both cancels are counted`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        val outcome = withDuring(kit, order.id) {
            kit.gateway.consoleCancel("pay_1", 1000) // txn_1 - 같은 금액의 콘솔 취소
            kit.refunds.sync("op_2", order.id) // 맞추기가 그것을 우리 PENDING 줄에 짝지었다
        }

        val ours = kit.store.findRefund(outcome.refund.id)!!
        assertEquals(PaymentRefundStatus.SUCCEEDED, ours.status)
        assertEquals("txn_1", ours.providerRefundId)
        val provider = kit.store.listRefunds(order.id).single { it.origin == PaymentRefundOrigin.PROVIDER }
        assertEquals("txn_2", provider.providerRefundId, "our own cancel was not the one the line took")
        assertEquals(2000, kit.order(order.id).refundedAmount)
        assertEquals(kit.gateway.payment("pay_1")!!.refundedTotal, kit.order(order.id).refundedAmount)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
    }

    @Test
    fun `the response path also refuses a success that would push the total past the paid amount - the line stays pending and the order is flagged`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        val outcome = withDuring(kit, order.id) {
            // 그 사이 장부가 2,500 을 더 환불된 것으로 맞췄다(1,000 + 2,500 > 3,000)
            kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(order.id)!!.copy(refundedAmount = 2500)) }
        }

        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status, "it is not marked succeeded past what was paid")
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(2500, kit.order(order.id).refundedAmount)
    }

    // ---- M-1: 늦은 결과의 표시는 SYNC 로만 풀린다 ----

    private fun closeLineDuringRefund(kit: PaymentLedgerKit, orderId: String): RefundOutcome =
        withDuring(kit, orderId) {
            val line = kit.store.listRefunds(orderId).single()
            kit.store.inTransaction { tx ->
                tx.lockOrder(orderId)
                kit.writer.transitionRefund(tx, tx.lockRefund(line.id)!!, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, PaymentActor.SYSTEM, null, RefundSettlement.NOT_APPLIED) {
                    it.copy(failureCode = RefundSettlement.NOT_APPLIED, completedAt = kit.time.now())
                }
            }
        }

    private fun convergeAs(kit: PaymentLedgerKit, orderId: String, source: ConvergeSource) {
        val snapshot = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!
        kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(orderId)!!, snapshot, ConvergeContext(PaymentMode.TEST, source)) }
    }

    @Test
    fun `the mark of a late result is not cleared by an automatic reconcile or a webhook - only sync clears it`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        closeLineDuringRefund(kit, order.id)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)

        convergeAs(kit, order.id, ConvergeSource.WEBHOOK)
        convergeAs(kit, order.id, ConvergeSource.RECONCILE)
        convergeAs(kit, order.id, ConvergeSource.REFUND)

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason, "the numbers agree, but a person has not looked at the late result")

        kit.refunds.sync(op, order.id)

        assertNull(kit.order(order.id).reviewReason)
    }

    @Test
    fun `a webhook delivered after a late result does not clear the mark either`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        closeLineDuringRefund(kit, order.id)

        kit.deliver(kit.gateway.webhookDelivery(eventType = "PAYMENT_STATUS_CHANGED", paymentKey = "pay_1", orderId = order.id))
        kit.runWebhooks()

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
    }

    // ---- M-6: 거래 키 없는 환불됨 응답이 닫힌 줄에 도착했다 ----

    @Test
    fun `a refunded answer without a transaction key on a closed failed line is not dropped - the order is flagged and the trail says so`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveWithoutTransactionKey)

        val outcome = closeLineDuringRefund(kit, order.id)

        assertEquals(PaymentRefundStatus.FAILED, outcome.refund.status)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        val trail = kit.store.listEvents(order.id).filter { it.refundId == outcome.refund.id }.map { it.providerCode }
        assertTrue("LATE_RESULT" in trail, "$trail")
        assertEquals(kit.gateway.payment("pay_1")!!.refundedTotal, kit.order(order.id).refundedAmount, "the cancel that went out is in the ledger too")
    }
}
