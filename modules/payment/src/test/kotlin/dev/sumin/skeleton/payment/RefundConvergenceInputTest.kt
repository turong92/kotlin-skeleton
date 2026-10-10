package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 결제사의 취소 목록을 장부에 맞추는 입구의 걸름(식별자 모양 · 중복 · 0원). */
class RefundConvergenceInputTest {
    private fun converge(kit: PaymentLedgerKit, orderId: String, cancels: List<ProviderRefund>, remaining: Long): ConvergeResult {
        val snapshot = kit.gateway.lookup(PaymentLookupQuery("pay_1", null))!!
            .copy(status = ProviderPaymentStatus.PARTIALLY_REFUNDED, refunds = cancels, remaining = PaymentAmount(remaining, "KRW"))
        return kit.store.inTransaction { tx -> kit.convergence.converge(tx, tx.lockOrder(orderId)!!, snapshot, ConvergeContext(PaymentMode.TEST, ConvergeSource.WEBHOOK)) }
    }

    private fun cancel(key: String, amount: Long) = ProviderRefund(key, PaymentAmount(amount, "KRW"), java.time.Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `a duplicate transaction key and a zero-amount cancel in the provider's list do not break the reconcile`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        val result = converge(kit, order.id, listOf(cancel("txn_a", 1000), cancel("txn_a", 1000), cancel("txn_zero", 0)), remaining = 2000)

        val lines = kit.store.listRefunds(order.id)
        assertEquals(listOf("txn_a"), lines.map { it.providerRefundId })
        assertEquals(1000, result.order.refundedAmount)
        assertNull(result.order.reviewReason, "what is left agrees with the one real cancel")
    }

    @Test
    fun `a transaction key that is not an identifier is dropped by the same filter as on the response path, and the leftover difference flags the order`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        val result = converge(kit, order.id, listOf(cancel("a cancel with spaces", 1000)), remaining = 2000)

        assertEquals(emptyList(), kit.store.listRefunds(order.id))
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, result.order.reviewReason, "money moved at the provider that the ledger could not take in")
    }
}
