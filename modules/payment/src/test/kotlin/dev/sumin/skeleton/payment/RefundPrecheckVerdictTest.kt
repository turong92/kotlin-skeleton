package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 환불 전 확인의 "보내지 않는다"는 검토 이유 **칸**이 아니라 **판정 값**(맞춘 뒤 결제사의 남은 금액 대 장부의 남은 금액)으로 가른다(B5 리뷰 2회차 N-2) —
 * 칸을 다른 이유가 차지한 주문에서도 숫자가 어긋나면 환불은 나가지 않는다. 그리고 `REFUND_MISMATCH` 가 이미 있으면 보내지 않는다는 검사는 선점 락 안에서 한 번 더 본다(M-2).
 * `MODE_MISMATCH` 는 지금 모드가 주문과 같을 때 `sync` 가 푼다(ⓒ).
 */
class RefundPrecheckVerdictTest {
    private val op = "op_1"
    private val taken = listOf(
        PaymentReviewReason.MODE_MISMATCH, PaymentReviewReason.AMOUNT_MISMATCH, PaymentReviewReason.ORDER_MISMATCH,
        PaymentReviewReason.CONFIRM_UNRESOLVED, PaymentReviewReason.FULFILMENT_UNRESOLVED, PaymentReviewReason.REFUND_APPLY_UNRESOLVED,
    )

    private fun flag(kit: PaymentLedgerKit, orderId: String, reason: PaymentReviewReason) {
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(orderId)!!, reason, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE)) }
    }

    private fun skewLedger(kit: PaymentLedgerKit, orderId: String) {
        kit.advance(Duration.ofSeconds(1))
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(orderId)!!.copy(refundedAmount = 500)) } // 장부만 500 이 환불됐다고 믿는다
    }

    @Test
    fun `a direct refund is not sent when the numbers disagree even though another reason already holds the column`() {
        taken.forEach { reason ->
            val kit = PaymentLedgerKit()
            val order = kit.paid()
            flag(kit, order.id, reason)
            skewLedger(kit, order.id)

            assertFailsWith<PaymentRefundNotAllowedException>("reason=$reason") { kit.refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("k-1")) }

            assertEquals(0, kit.gateway.refundCalls.size, "reason=$reason: no provider refund call")
        }
    }

    @Test
    fun `an approval is not sent when the numbers disagree even though another reason already holds the column`() {
        taken.forEach { reason ->
            val kit = PaymentLedgerKit()
            val order = kit.paid()
            val request = kit.refunds.request("acc_1", order.id, "DUPLICATE", 500, IdempotencyKey("req")).refund
            flag(kit, order.id, reason)
            skewLedger(kit, order.id)

            assertFailsWith<PaymentRefundNotAllowedException>("reason=$reason") { kit.refunds.approve(op, request.id) }

            assertEquals(0, kit.gateway.refundCalls.size, "reason=$reason: no provider refund call")
            assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(request.id)!!.status)
        }
    }

    @Test
    fun `a supply reason in the column is replaced by the refund mismatch the check finds`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.FULFILMENT_UNRESOLVED)
        skewLedger(kit, order.id)

        assertFailsWith<PaymentRefundNotAllowedException> { kit.refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("k-1")) }

        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
    }

    @Test
    fun `numbers that agree send the refund even when another reason holds the column`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.FULFILMENT_UNRESOLVED)

        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("k-1"))

        assertEquals(PaymentRefundStatus.SUCCEEDED, outcome.refund.status)
    }

    // ---- M-2 ----

    /** 두 번째 트랜잭션(= 선점)이 열리기 직전에 [before] 가 끼는 저장소 — 확인과 선점 사이의 틈. */
    private fun gapStore(kit: PaymentLedgerKit, before: () -> Unit): PaymentLedgerStore {
        var opened = 0
        return object : PaymentLedgerStore by kit.store {
            override fun <T> inTransaction(block: (PaymentLedgerTx) -> T): T {
                if (++opened == 2) before()
                return kit.store.inTransaction(block)
            }
        }
    }

    @Test
    fun `a refund mismatch that lands between the pre-check and the claim stops the direct refund inside the lock`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val store = gapStore(kit) { flag(kit, order.id, PaymentReviewReason.REFUND_MISMATCH) }
        val refunds = PaymentRefunds(store, kit.tasks, kit.router, kit.convergence, kit.applier, kit.writer, kit.properties)

        assertFailsWith<PaymentRefundNotAllowedException> { refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("k-1")) }

        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(emptyList(), kit.store.listRefunds(order.id))
    }

    @Test
    fun `a refund mismatch that lands between the pre-check and the claim stops the approval inside the lock - the request stays requested`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val request = kit.refunds.request("acc_1", order.id, "DUPLICATE", 500, IdempotencyKey("req")).refund
        val store = gapStore(kit) { flag(kit, order.id, PaymentReviewReason.REFUND_MISMATCH) }
        val refunds = PaymentRefunds(store, kit.tasks, kit.router, kit.convergence, kit.applier, kit.writer, kit.properties)

        assertFailsWith<PaymentRefundNotAllowedException> { refunds.approve(op, request.id) }

        assertEquals(0, kit.gateway.refundCalls.size)
        assertEquals(PaymentRefundStatus.REQUESTED, kit.store.findRefund(request.id)!!.status)
    }

    // ---- ⓒ ----

    @Test
    fun `sync releases a mode mismatch when the gateway's mode is the order's mode - and says so in the history`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.MODE_MISMATCH)

        val synced = kit.refunds.sync(op, order.id)

        assertNull(synced.reviewReason)
        assertNull(kit.order(order.id).reviewReason)
        assertEquals("MODE_MISMATCH_CLEARED", kit.store.listEvents(order.id).last().providerCode)
    }

    @Test
    fun `sync keeps the mode mismatch while the gateway's mode differs from the order's`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.MODE_MISMATCH)
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(order.id)!!.copy(providerMode = PaymentMode.LIVE)) }

        assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, order.id) }

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
    }

    @Test
    fun `sync leaves other reasons alone when it releases nothing`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.AMOUNT_MISMATCH)

        kit.refunds.sync(op, order.id)

        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
    }
}
