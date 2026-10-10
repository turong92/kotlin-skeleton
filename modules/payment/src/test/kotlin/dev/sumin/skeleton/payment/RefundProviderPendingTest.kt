package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 결제사가 "접수했다(처리 중)"고 답한 환불은 타임아웃(아무 답이 없었다)과 다르다 — 받았다고 말한 요청을 "안 나갔다"로 닫으면 늦은 적용(스펙 §15)이 예외가 아니라 정상 경로가 된다.
 * 줄은 `failure_code=PROVIDER_PENDING`(이력에도 같은 코드)으로 남고 `NOT_APPLIED` 로 닫지 않는다: 정산 작업이 재시도하다 마지막 시도(와 죽은 작업)에서 `REFUND_MISMATCH` 를 찍는다.
 */
class RefundProviderPendingTest {
    private val op = "op_1"
    private val settleDelay = Duration.ofMinutes(2)

    private fun acknowledged(kit: PaymentLedgerKit): PaymentRefund {
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.AnswerPending)
        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        assertEquals(PaymentRefundStatus.PENDING, outcome.refund.status)
        return outcome.refund
    }

    private fun runSettle(kit: PaymentLedgerKit) = kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE))

    @Test
    fun `a refund the provider said it accepted is not closed as failed when the lookup does not show it - the job retries and flags on the last attempt`() {
        val kit = PaymentLedgerKit(PaymentProperties(reconcile = PaymentProperties.Reconcile(maxAttempts = 3)))
        val line = acknowledged(kit)
        kit.advance(settleDelay)

        runSettle(kit)
        runSettle(kit)

        val task = kit.tasks.all(PaymentTaskTypes.REFUND_RECONCILE).single()
        assertEquals(State.PENDING, task.state)
        assertNull(kit.order(line.orderId).reviewReason, "not before the last attempt")
        runSettle(kit)

        assertEquals(State.DEAD, task.state)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status, "never closed as not-applied")
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(line.orderId).reviewReason)
    }

    @Test
    fun `an acknowledged refund the lookup shows later becomes succeeded exactly once`() {
        val kit = PaymentLedgerKit()
        val line = acknowledged(kit)
        kit.advance(settleDelay)
        runSettle(kit)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)

        kit.gateway.applyLate() // the provider finishes what it accepted
        runSettle(kit)
        runSettle(kit)
        kit.refunds.sync(op, line.orderId)

        val done = kit.store.findRefund(line.id)!!
        assertEquals(PaymentRefundStatus.SUCCEEDED, done.status)
        assertEquals("txn_1", done.providerRefundId)
        assertEquals(1000, kit.order(line.orderId).refundedAmount, "counted once")
        assertEquals(1, kit.store.listRefunds(line.orderId).size)
        assertEquals(1, kit.gateway.refundCalls.size)
        assertNull(kit.order(line.orderId).reviewReason)
    }
}
