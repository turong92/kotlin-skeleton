package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 운영자가 요청 금액을 고쳐 승인한다 — 돈이 걸린 것: 상한을 넘으면 결제사를 부르지 않고, 결제사에는 고친 금액이 간다. */
class RefundApproveAmountTest {
    private val op = "op_1"

    private fun requested(kit: PaymentLedgerKit, amount: Long = 2000): PaymentRefund {
        val order = kit.paid() // 3,000원
        return kit.refunds.request("acc_1", order.id, "DUPLICATE", amount, IdempotencyKey("k1")).refund
    }

    @Test
    fun `a lower amount is what the provider gets, and the line keeps what was asked for`() {
        val kit = PaymentLedgerKit()
        val line = requested(kit)

        val outcome = kit.refunds.approve(op, line.id, 1500)

        assertEquals(1500, kit.gateway.refundCalls.single().amount.amount)
        assertEquals(1500, outcome.refund.amount.amount)
        assertEquals(2000, outcome.refund.requestedAmount)
        assertEquals(1500, outcome.order.refundedAmount)
    }

    @Test
    fun `an amount above the request, above what is refundable, or not positive is refused before the provider is called`() {
        val kit = PaymentLedgerKit()
        val line = requested(kit)

        listOf(2001L, 3000L, 0L, -5L).forEach { assertFailsWith<PaymentRefundAmountExceededException> { kit.refunds.approve(op, line.id, it) } }

        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(line.id)!!.status)
    }

    @Test
    fun `approving without an amount still refunds the requested amount and records nothing extra`() {
        val kit = PaymentLedgerKit()
        val line = requested(kit)

        val outcome = kit.refunds.approve(op, line.id)

        assertEquals(2000, kit.gateway.refundCalls.single().amount.amount)
        assertNull(outcome.refund.requestedAmount)
    }
}
