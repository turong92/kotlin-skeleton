package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.ConvergeContext
import dev.sumin.skeleton.payment.ConvergeSource
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentActor
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentEventType
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentRefundNotAllowedException
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 검토 이유의 우선순위 · 접수된 환불 줄의 출구 · 환불 전 확인의 판정(B5 리뷰 2회차)을 진짜 DB 에서 — 열린 줄이 쥔 `open_key` 가 줄이 닫히면 풀려 다음 환불이 나가는지,
 * 칸을 다른 이유가 차지한 주문에서 불일치면 결제사 환불 호출이 0 인지, 두 방언 모두.
 */
class RefundReviewDbTest {
    private val op = "op_1"

    @BeforeTest
    fun clean() = PaymentDb.clean()

    @Test
    fun `an acknowledged refund that the settle job gave up on is closed by a sync after the deadline and the open key is released`() {
        val kit = JdbcLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.AnswerPending)
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        kit.refundReconcile.onDead(PaymentTaskRun(PaymentTaskTypes.REFUND_RECONCILE, line.id, 12, 12))
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        kit.time.advance(Duration.ofHours(2))

        kit.refunds.sync(op, order.id)

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason, "before the deadline the flag stays")
        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("k-2")) }

        kit.time.advance(Duration.ofHours(23))
        kit.refunds.sync(op, order.id)

        val closed = kit.store.findRefund(line.id)!!
        assertEquals(PaymentRefundStatus.FAILED, closed.status)
        assertNull(closed.openKey)
        assertEquals("PROVIDER_PENDING_EXPIRED", kit.store.listEvents(order.id).last { it.refundId == line.id && it.type == PaymentEventType.REFUND_FAILED }.providerCode)
        assertNull(kit.order(order.id).reviewReason)
        assertEquals(PaymentRefundStatus.SUCCEEDED, kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-3")).refund.status)
    }

    @Test
    fun `a mismatch is not sent when another money reason holds the column - no provider refund call`() {
        val kit = JdbcLedgerKit()
        val order = kit.paid()
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(order.id)!!, PaymentReviewReason.MODE_MISMATCH, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE)) }
        kit.time.advance(Duration.ofSeconds(1))
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(order.id)!!.copy(refundedAmount = 500)) }

        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("k-1")) }

        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason, "first-marked wins among money reasons")
    }

    @Test
    fun `a success that arrives after the line was closed revives that line on the real rows - once, and no extra line`() {
        val kit = JdbcLedgerKit()
        val order = kit.paid()
        kit.gateway.duringRefund = {
            kit.gateway.duringRefund = null
            val line = kit.store.listRefunds(order.id).single()
            kit.store.inTransaction { tx ->
                tx.lockOrder(order.id)
                kit.writer.transitionRefund(tx, tx.lockRefund(line.id)!!, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, PaymentActor.SYSTEM, null, "NOT_APPLIED") {
                    it.copy(failureCode = "NOT_APPLIED", completedAt = kit.time.now())
                }
            }
        }

        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        kit.refunds.sync(op, order.id)

        assertEquals(PaymentRefundStatus.SUCCEEDED, outcome.refund.status)
        val lines = kit.store.listRefunds(order.id)
        assertEquals(1, lines.size)
        assertEquals("txn_1", lines.single().providerRefundId)
        assertEquals(1000, kit.order(order.id).refundedAmount)
        assertNull(kit.order(order.id).reviewReason)
    }

    @Test
    fun `the mark of a late result survives an automatic reconcile on the real rows and a sync clears it`() {
        val kit = JdbcLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveOtherAmount(PaymentAmount(500, "KRW"))) // 다른 금액이라 닫힌 줄을 되살릴 수 없다
        kit.gateway.duringRefund = {
            kit.gateway.duringRefund = null
            val line = kit.store.listRefunds(order.id).single()
            kit.store.inTransaction { tx ->
                tx.lockOrder(order.id)
                kit.writer.transitionRefund(tx, tx.lockRefund(line.id)!!, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, PaymentActor.SYSTEM, null, "NOT_APPLIED") {
                    it.copy(failureCode = "NOT_APPLIED", completedAt = kit.time.now())
                }
            }
        }
        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        assertEquals("LATE_RESULT", kit.store.findRefund(outcome.refund.id)!!.failureCode)
        val snapshot = kit.gateway.lookup(dev.sumin.skeleton.payment.PaymentLookupQuery("pay_1", null))!!

        kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(order.id)!!, snapshot, ConvergeContext(PaymentMode.TEST, ConvergeSource.WEBHOOK)) }
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)

        kit.refunds.sync(op, order.id)
        assertNull(kit.order(order.id).reviewReason)
    }
}
