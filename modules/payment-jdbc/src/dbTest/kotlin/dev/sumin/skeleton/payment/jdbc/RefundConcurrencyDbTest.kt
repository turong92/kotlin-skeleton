package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundAmountExceededException
import dev.sumin.skeleton.payment.PaymentRefundInProgressException
import dev.sumin.skeleton.payment.PaymentRefundNotAllowedException
import dev.sumin.skeleton.payment.PaymentRefundOrigin
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 환불을 진짜 저장소 · 진짜 락 위에서 경주시킨다(스펙 §3 의 12 · 13 · 18, §13.2): 교착 없이 끝나고(`race` 의 60초 제한이 막힘을 잡는다) 승자는 하나, 합은 결제액을 넘지 않는다.
 * 락 순서는 늘 주문 → 환불이라 모든 경주가 `lockOrder` 에서 줄을 선다.
 */
class RefundConcurrencyDbTest {
    private val op = "op_1"

    @BeforeTest
    fun clean() = PaymentDb.clean()


    @Test
    fun `sixteen syncs finding the same provider cancel leave one provider line and an apply that runs once`() {
        val kit = JdbcLedgerKit()
        val order = kit.paid()
        kit.gateway.consoleCancel("pay_1", 1000) // 결제사 관리 화면에서 한 번 취소했다 - 장부는 모른다

        val results = race(16) { kit.refunds.sync(op, order.id) }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        val lines = kit.store.listRefunds(order.id)
        assertEquals(1, lines.size)
        assertEquals(PaymentRefundOrigin.PROVIDER, lines.single().origin)
        assertEquals("txn_1", lines.single().providerRefundId)
        assertEquals(1000, kit.order(order.id).refundedAmount, "counted once")
        // 맞춘 뒤에도 회수가 안 끝난 줄은 맞추기마다 회수 작업을 한 번씩 다시 넣는다(최대 16개) - 작업이 쌓여도 회수는 한 번이다
        val applyJobs = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.REFUND_APPLY)
        assertTrue(applyJobs in 1..16, "apply jobs: $applyJobs")
        val lineId = lines.single().id
        repeat(applyJobs.toInt() + 1) { kit.applier.tryApply(lineId) }
        assertEquals(1, kit.listener.refunded.size, "the app's refund hook ran once")
    }

    @Test
    fun `refunds racing with each other, an approval, a sync and the settle job end without deadlock and never exceed the paid amount`() {
        repeat(5) { round ->
            PaymentDb.clean()
            val kit = JdbcLedgerKit()
            val order = kit.paid(key = "key-$round", paymentKey = "pay_$round")
            val request = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("req-$round")).refund
            kit.gateway.scriptRefund(RefundScript.TimeoutApplied) // 하나는 모름으로 끝나 열린 채 남는다

            val results = race(8) { n ->
                when (n) {
                    0 -> kit.refunds.approve(op, request.id)
                    1 -> kit.refunds.sync(op, order.id)
                    2 -> kit.refundReconcile.handle(PaymentTaskRun(PaymentTaskTypes.REFUND_RECONCILE, request.id, 1, 12))
                    else -> kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("op-$round-$n"))
                }
            }

            val unexpected = results.mapNotNull { it.exceptionOrNull() }.filterNot {
                it is PaymentRefundInProgressException || it is PaymentRefundNotAllowedException || it is PaymentRefundAmountExceededException || it is dev.sumin.skeleton.payment.PaymentTaskRetryException
            }
            assertEquals(emptyList(), unexpected, "round $round")
            val final = kit.order(order.id)
            val succeeded = kit.store.listRefunds(order.id).filter { it.status == PaymentRefundStatus.SUCCEEDED }
            assertTrue(final.refundedAmount <= 3000, "round $round: ${final.refundedAmount}")
            assertEquals(succeeded.sumOf { it.amount.amount }, final.refundedAmount, "round $round: the order total is the sum of the succeeded lines")
            assertTrue(kit.store.listRefunds(order.id).count { it.openKey != null } <= 1, "round $round: one open slot")
            assertEquals(kit.gateway.payment("pay_$round")!!.refundedTotal, final.refundedAmount + openAmount(kit, order.id), "round $round: the provider's total is the ledger's plus what is still unknown")
        }
    }

    private fun openAmount(kit: JdbcLedgerKit, orderId: String): Long =
        kit.store.listRefunds(orderId).filter { it.status == PaymentRefundStatus.PENDING }.sumOf { it.amount.amount }
}
