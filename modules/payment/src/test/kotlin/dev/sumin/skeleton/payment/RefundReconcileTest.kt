package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RefundReconcileTest {
    private val op = "op_1"
    private val settleDelay = Duration.ofMinutes(2)

    /** 환불 응답을 못 받은 줄 하나(1,000원). */
    private fun timedOut(kit: PaymentLedgerKit, script: RefundScript): PaymentRefund {
        val order = kit.paid()
        kit.gateway.scriptRefund(script)
        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        return outcome.refund
    }

    private fun runSettle(kit: PaymentLedgerKit) = kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE))

    @Test
    fun `a refund that timed out but was applied is settled once`() {
        val kit = PaymentLedgerKit()
        val line = timedOut(kit, RefundScript.TimeoutApplied)
        kit.advance(settleDelay)

        runSettle(kit)
        runSettle(kit)

        val done = kit.store.findRefund(line.id)!!
        assertEquals(PaymentRefundStatus.SUCCEEDED, done.status)
        assertEquals("txn_1", done.providerRefundId, "the provider's own cancel, found by asking")
        assertEquals(1000, kit.order(line.orderId).refundedAmount, "counted once")
        assertEquals(1, kit.gateway.refundCalls.size, "settling asks, it never sends the refund again")
        assertEquals(1, kit.store.listRefunds(line.orderId).size, "no second line for the same cancel")
        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY))
        assertEquals(1, kit.listener.refunded.size)
    }

    @Test
    fun `a refund that timed out and was not applied is failed and the order is refundable again`() {
        val kit = PaymentLedgerKit()
        val line = timedOut(kit, RefundScript.TimeoutNotApplied)
        kit.advance(settleDelay)

        runSettle(kit)

        val failed = kit.store.findRefund(line.id)!!
        assertEquals(PaymentRefundStatus.FAILED, failed.status)
        assertNull(failed.openKey)
        assertEquals(3000, kit.refunds.view(line.orderId).summary.refundableAmount)
        val again = kit.refunds.refund(op, line.orderId, "OPERATOR", 1000, IdempotencyKey("k-2"))
        assertEquals(PaymentRefundStatus.SUCCEEDED, again.refund.status)
        assertNotEquals(kit.gateway.refundCalls[0].idempotencyKey, kit.gateway.refundCalls[1].idempotencyKey, "and a new provider key")
    }

    @Test
    fun `the settle job does not run before the settle delay and a manual settle respects it too`() {
        val kit = PaymentLedgerKit()
        val line = timedOut(kit, RefundScript.TimeoutNotApplied)
        kit.advance(settleDelay.minusSeconds(1))
        val lookupsBefore = kit.gateway.lookupCalls.size

        assertEquals(0, runSettle(kit))
        kit.refunds.sync(op, line.orderId)

        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status, "the request may still be on its way")
        assertEquals(lookupsBefore + 1, kit.gateway.lookupCalls.size)
        kit.advance(Duration.ofSeconds(1))
        runSettle(kit)
        assertEquals(PaymentRefundStatus.FAILED, kit.store.findRefund(line.id)!!.status)
    }

    @Test
    fun `a request that lands at the provider after the lookup is caught by the next refund's pre-check`() {
        val kit = PaymentLedgerKit()
        val line = timedOut(kit, RefundScript.TimeoutAppliedLater)

        kit.gateway.applyLate() // the provider finally processes it
        val next = kit.refunds.refund(op, line.orderId, "OPERATOR", 500, IdempotencyKey("k-2"))

        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.store.findRefund(line.id)!!.status, "the pre-check settled the old line first")
        assertEquals(PaymentRefundStatus.SUCCEEDED, next.refund.status)
        assertEquals(1500, next.order.refundedAmount)
        assertEquals(3000 - 1500, kit.gateway.payment("pay_1")!!.remaining.amount)
    }

    @Test
    fun `a provider that is still unreachable keeps the line pending and the job retrying - on the last attempt the order is flagged`() {
        val kit = PaymentLedgerKit(PaymentProperties(reconcile = PaymentProperties.Reconcile(maxAttempts = 2)))
        val line = timedOut(kit, RefundScript.TimeoutNotApplied)
        kit.advance(settleDelay)
        kit.gateway.failLookups(2)

        runSettle(kit)
        val task = kit.tasks.all(PaymentTaskTypes.REFUND_RECONCILE).single()
        assertEquals(State.PENDING, task.state)
        assertNull(kit.order(line.orderId).reviewReason)
        runSettle(kit)

        assertEquals(State.DEAD, task.state)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(line.orderId).reviewReason)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status, "the open line keeps the next refund out")
    }

    @Test
    fun `a console cancel that lands between the send and the settle is absorbed first, so the line is judged on the right numbers`() {
        val kit = PaymentLedgerKit()
        val line = timedOut(kit, RefundScript.TimeoutNotApplied)
        kit.gateway.consoleCancel("pay_1", 700) // 장부의 1,000 과도 0 과도 다른 취소가 끼었다 — 흡수한 뒤 남은 금액은 같으니 NOT_APPLIED 로 읽힌다
        kit.advance(settleDelay)

        runSettle(kit)

        assertEquals(PaymentRefundStatus.FAILED, kit.store.findRefund(line.id)!!.status)
        assertEquals(700, kit.order(line.orderId).refundedAmount)
        assertNull(kit.order(line.orderId).reviewReason)
    }

    @Test
    fun `numbers that fit neither outcome flag the order and keep the line open for a person`() {
        val kit = PaymentLedgerKit()
        val line = timedOut(kit, RefundScript.TimeoutNotApplied)
        kit.advance(settleDelay)
        val skewed = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!.copy(remaining = PaymentAmount(1234, "KRW"))

        val result = kit.store.inTransaction { tx ->
            RefundSettlement(kit.convergence, kit.writer).settle(
                tx, tx.lockOrder(line.orderId)!!, line.id, skewed, kit.time.now(),
                ConvergeContext(PaymentMode.TEST, ConvergeSource.REFUND), PaymentProperties.Refund(settleDelay = settleDelay),
            )
        }

        assertEquals(RefundSettlement.Result.MISMATCH, result)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(line.orderId).reviewReason)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
    }

    @Test
    fun `an order created under another mode is flagged and the job dies without asking`() {
        val kit = PaymentLedgerKit()
        val line = timedOut(kit, RefundScript.TimeoutNotApplied)
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(settleDelay)
        val lookups = kit.gateway.lookupCalls.size

        runSettle(kit)

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(line.orderId).reviewReason)
        assertEquals(State.DEAD, kit.tasks.all(PaymentTaskTypes.REFUND_RECONCILE).single().state)
        assertEquals(lookups, kit.gateway.lookupCalls.size)
    }
}
