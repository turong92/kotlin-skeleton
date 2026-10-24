package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 웹훅 처리기 **경로**(수신 → 작업 → 맞추기)로 보낸 위조 웹훅 한 번이 사람이 봐야 할 표시를 지우지 못한다 —
 * 늦게 도착한 환불 결과(`LATE_RESULT`) · 결제사가 요청과 다른 금액을 취소한 표시(`RESULT_MISMATCH`) · 접수된 `PENDING` 줄이 있는 주문의 `REFUND_MISMATCH`.
 * 푸는 길은 사람이 누른 「결제사와 맞추기」뿐이다.
 */
class WebhookKeepsReviewMarksTest {
    private val op = "op_1"

    private fun webhookArrives(kit: PaymentLedgerKit, orderId: String) {
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-${System.nanoTime()}", orderId = orderId))
        kit.runWebhooks()
    }

    @Test
    fun `a webhook does not clear the mark of a late refund result`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveOtherAmount(PaymentAmount(500, "KRW"))) // 다른 금액이라 닫힌 줄을 되살릴 수 없다 - 표시가 남는다
        kit.gateway.duringRefund = {
            kit.gateway.duringRefund = null
            val line = kit.store.listRefunds(order.id).single()
            kit.store.inTransaction { tx ->
                tx.lockOrder(order.id)
                kit.writer.transitionRefund(tx, tx.lockRefund(line.id)!!, PaymentRefundStatus.FAILED, PaymentEventType.REFUND_FAILED, PaymentActor.SYSTEM, null, RefundSettlement.NOT_APPLIED) {
                    it.copy(failureCode = RefundSettlement.NOT_APPLIED, completedAt = kit.time.now())
                }
            }
        }
        kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(1, kit.store.listRefunds(order.id).count { it.failureCode == RefundSettlement.LATE_RESULT })

        webhookArrives(kit, order.id)

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason, "a forged webhook cannot make the late result look fine")
        assertEquals(1, kit.store.listRefunds(order.id).count { it.failureCode == RefundSettlement.LATE_RESULT })
        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.listWebhookEvents(null, null, 10).single().status)
    }

    @Test
    fun `a webhook does not clear the mark of a refund whose amount differed from the request`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.ApproveOtherAmount(PaymentAmount(500, "KRW")))
        kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        val line = kit.store.listRefunds(order.id).single { it.failureCode == RefundSettlement.RESULT_MISMATCH }

        webhookArrives(kit, order.id)

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(RefundSettlement.RESULT_MISMATCH, kit.store.findRefund(line.id)!!.failureCode)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(line.id)!!.status)
    }
}
