package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 결제사가 접수했다(`PROVIDER_PENDING`)고 답한 환불 줄의 출구(B5 리뷰 2회차 N-1). 경로: ① 줄 `PENDING` + `PROVIDER_PENDING` ② 정산 작업이 재시도하다 죽고 `REFUND_MISMATCH` ③ 운영자가 「결제사와 맞추기」.
 * ③ 이 표시를 지우고 줄을 그대로 두면 주문이 「확인 필요」에서 사라지고 다음 환불이 영원히 막힌다 — `sync` 는 접수된 줄이 남아 있으면 표시를 풀지 않고,
 * 줄의 `sent_at` 이 `provider-pending-timeout` 을 넘었고 조회에 여전히 안 보이면 `NOT_APPLIED` 로 닫는다. 정산 작업(자동)은 닫지 않는다.
 */
class RefundProviderPendingExpiryTest {
    private val op = "op_1"
    private val settleDelay = Duration.ofMinutes(2)
    private val timeout = Duration.ofHours(24)

    private fun kit() = PaymentLedgerKit(PaymentProperties(reconcile = PaymentProperties.Reconcile(maxAttempts = 3)))

    /** ① ② 까지: 접수된 줄 + 죽은 정산 작업 + `REFUND_MISMATCH`. */
    private fun deadAcknowledged(kit: PaymentLedgerKit): PaymentRefund {
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.AnswerPending)
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        kit.advance(settleDelay)
        repeat(3) { kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE)) }
        assertEquals(State.DEAD, kit.tasks.all(PaymentTaskTypes.REFUND_RECONCILE).single().state)
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(line.orderId).reviewReason)
        assertEquals("PROVIDER_PENDING", kit.store.findRefund(line.id)!!.failureCode)
        return line
    }

    @Test
    fun `a sync before the deadline keeps the flag and the open line - the order stays in the needs-attention list and refunds stay blocked`() {
        val kit = kit()
        val line = deadAcknowledged(kit)
        kit.advance(timeout.minusHours(1))

        val synced = kit.refunds.sync(op, line.orderId)

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, synced.reviewReason)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
        assertEquals(PaymentRefundStatus.PENDING, kit.refunds.view(line.orderId).refunds.single().status)
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.refund(op, line.orderId, "OPERATOR", 500, IdempotencyKey("k-2")) }
        assertEquals(1, kit.gateway.refundCalls.size)
    }

    @Test
    fun `an acknowledged line that was never flagged keeps its mark after a sync as well - the settle result flags nothing new`() {
        val kit = kit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.AnswerPending)
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        kit.advance(settleDelay)

        kit.refunds.sync(op, order.id)

        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
        assertEquals("PROVIDER_PENDING", kit.store.findRefund(line.id)!!.failureCode)
    }

    @Test
    fun `the settle job does not close an acknowledged line even long after the deadline - only a person's sync does`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.AnswerPending)
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        kit.advance(timeout.plusHours(1))

        kit.runTasks(setOf(PaymentTaskTypes.REFUND_RECONCILE))

        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
    }

    @Test
    fun `a sync after the deadline closes the line as not-applied with the reason in the history, releases the flag and the order can be refunded again`() {
        val kit = kit()
        val line = deadAcknowledged(kit)
        kit.advance(timeout)

        val synced = kit.refunds.sync(op, line.orderId)

        val closed = kit.store.findRefund(line.id)!!
        assertEquals(PaymentRefundStatus.FAILED, closed.status)
        assertEquals("NOT_APPLIED", closed.failureCode)
        assertNull(closed.openKey)
        val event = kit.store.listEvents(line.orderId).last { it.refundId == line.id && it.type == PaymentEventType.REFUND_FAILED }
        assertEquals("PROVIDER_PENDING_EXPIRED", event.providerCode)
        assertEquals(PaymentActor.OPERATOR, event.actor)
        assertNull(synced.reviewReason)

        val again = kit.refunds.refund(op, line.orderId, "OPERATOR", 1000, IdempotencyKey("k-2"))
        assertEquals(PaymentRefundStatus.SUCCEEDED, again.refund.status)
    }

    @Test
    fun `a request the provider applies after the line was closed is caught by the next sync as one provider line`() {
        val kit = kit()
        val line = deadAcknowledged(kit)
        kit.advance(timeout)
        kit.refunds.sync(op, line.orderId)
        assertEquals(PaymentRefundStatus.FAILED, kit.store.findRefund(line.id)!!.status)

        kit.gateway.applyLate() // 접수했던 요청이 이제 적용된다

        kit.refunds.sync(op, line.orderId)
        kit.refunds.sync(op, line.orderId)

        val provider = kit.store.listRefunds(line.orderId).filter { it.origin == PaymentRefundOrigin.PROVIDER }
        assertEquals(1, provider.size)
        assertEquals(1000, kit.order(line.orderId).refundedAmount)
        assertEquals(kit.gateway.payment("pay_1")!!.refundedTotal, kit.order(line.orderId).refundedAmount)
    }

    @Test
    fun `a sync after the deadline does not close the line when the lookup shows the cancel - it succeeds instead`() {
        val kit = kit()
        val line = deadAcknowledged(kit)
        kit.gateway.applyLate()
        kit.advance(timeout)

        kit.refunds.sync(op, line.orderId)

        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.store.findRefund(line.id)!!.status)
        assertNull(kit.order(line.orderId).reviewReason)
    }

    @Test
    fun `the deadline counts from the time of the lookup, not of the lock`() {
        val kit = kit()
        val line = deadAcknowledged(kit)
        kit.advance(timeout.minus(settleDelay).minusSeconds(10)) // 지금은 보낸 지 timeout - 10초
        kit.gateway.afterLookup = { kit.gateway.afterLookup = null; kit.advance(Duration.ofSeconds(30)) } // 조회는 기한 10초 전, 락은 기한 뒤

        kit.refunds.sync(op, line.orderId)

        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
    }

    @Test
    fun `the timeout must be positive and defaults to 24 hours`() {
        assertEquals(Duration.ofHours(24), PaymentProperties().refund.providerPendingTimeout)
        assertFailsWith<IllegalArgumentException> { PaymentProperties.Refund(providerPendingTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { PaymentProperties.Refund(providerPendingTimeout = Duration.ofSeconds(-1)) }
    }
}
