package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 환불 전 확인에서 결제사가 「받지 않았다」(`IN_PROGRESS` · `FAILED` · `EXPIRED`)고 답하면 — 장부는 결제됐다는데 결제사는 아니다 — 조회가 없을 때(null)와 같은 길로 막는다:
 * 환불 호출은 나가지 않고 환불 줄은 `PENDING` 으로 남지 않으며 `REFUND_MISMATCH` 가 찍힌다(`409 REFUND_NOT_ALLOWED`).
 */
class RefundPrecheckNotReceivedTest {
    private val op = "op_1"
    private val notReceived = listOf(ProviderPaymentStatus.IN_PROGRESS, ProviderPaymentStatus.FAILED, ProviderPaymentStatus.EXPIRED)

    @Test
    fun `a direct refund is refused before anything is sent when the provider says it never received the payment`() {
        notReceived.forEach { status ->
            val kit = PaymentLedgerKit()
            val order = kit.paid()
            kit.gateway.setProviderStatus("pay_1", status)

            assertFailsWith<PaymentRefundNotAllowedException>(status.name) { kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-$status")) }

            assertEquals(0, kit.gateway.refundCalls.size, status.name)
            assertEquals(emptyList(), kit.store.listRefunds(order.id), "no refund line is left behind: $status")
            assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason, status.name)
            assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status, status.name)
        }
    }

    @Test
    fun `an approval is refused the same way and the request stays requested`() {
        notReceived.forEach { status ->
            val kit = PaymentLedgerKit()
            val order = kit.paid()
            val line = kit.refunds.request("acc_1", order.id, "DUPLICATE", 1000, IdempotencyKey("q-$status")).refund
            kit.gateway.setProviderStatus("pay_1", status)

            assertFailsWith<PaymentRefundNotAllowedException>(status.name) { kit.refunds.approve(op, line.id) }

            assertEquals(0, kit.gateway.refundCalls.size, status.name)
            assertEquals(PaymentRefundStatus.REQUESTED, kit.store.listRefunds(order.id).single().status, status.name)
        }
    }
}
