package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `CONFIRM_UNRESOLVED` = "확인 중이었는데 끝내 결과를 못 알았다". 주문이 `FAILED` · `EXPIRED` 로 **닫히면** 돈이 안 나간 것으로 확정된 것이므로 그 표시는 비운다 —
 * 안 비우면 닫힌 주문이 「확인 필요」 목록에 영구히 남는다(B3 M1). 다른 이유는 건드리지 않는다.
 */
class ConfirmUnresolvedClearedTest {
    private fun unresolved(kit: PaymentLedgerKit): PaymentOrder {
        val created = kit.createAuthenticated("acc_1", paymentKey = "pay_u")
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        kit.orders.confirm("acc_1", created.id, "pay_u", created.amount)
        kit.store.inTransaction { tx ->
            tx.updateOrder(tx.lockOrder(created.id)!!.copy(reviewReason = PaymentReviewReason.CONFIRM_UNRESOLVED))
        }
        return kit.order(created.id)
    }

    @Test
    fun `when the provider later says the payment was aborted the order closes FAILED and the mark is gone`() {
        val kit = PaymentLedgerKit()
        val order = unresolved(kit)
        kit.gateway.abort("pay_u")

        kit.refunds.sync("operator_1", order.id)

        val closed = kit.order(order.id)
        assertEquals(PaymentOrderStatus.FAILED, closed.status)
        assertNull(closed.reviewReason, "a closed order that never took money is not waiting for review")
    }

    @Test
    fun `an unpaid order the expiry job closes EXPIRED loses the mark as well, and a money reason stays`() {
        val kit = PaymentLedgerKit()
        val withMark = kit.create("acc_1", key = "k-a").order
        val withMoneyReason = kit.create("acc_1", key = "k-b").order
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(withMark.id)!!.copy(reviewReason = PaymentReviewReason.CONFIRM_UNRESOLVED)) }
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(withMoneyReason.id)!!.copy(reviewReason = PaymentReviewReason.AMOUNT_MISMATCH)) }
        kit.advance(Duration.ofHours(2))

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(withMark.id).status)
        assertNull(kit.order(withMark.id).reviewReason)
        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(withMoneyReason.id).reviewReason)
    }
}
