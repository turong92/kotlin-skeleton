package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 검토 이유 칸은 하나라 우선순위를 둔다: **돈이 어긋난 이유**가 **지급 쪽 이유**를 덮어쓰고 반대는 안 된다. 같은 급끼리는 먼저 찍힌 것이 남는다.
 * 덮어쓰면 이력에 남기고 경보를 다시 낸다.
 */
class ReviewReasonPriorityTest {
    private val money = listOf(
        PaymentReviewReason.AMOUNT_MISMATCH, PaymentReviewReason.ORDER_MISMATCH, PaymentReviewReason.REFUND_MISMATCH,
        PaymentReviewReason.MODE_MISMATCH, PaymentReviewReason.CONFIRM_UNRESOLVED,
    )
    private val supply = listOf(PaymentReviewReason.FULFILMENT_UNRESOLVED, PaymentReviewReason.REFUND_APPLY_UNRESOLVED)

    private fun flag(kit: PaymentLedgerKit, orderId: String, reason: PaymentReviewReason) =
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(orderId)!!, reason, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE)).order }

    @Test
    fun `the priority table - what stays when a second reason arrives`() {
        val table = buildList {
            for (first in money + supply) for (second in money + supply) {
                val expected = if (first in supply && second in money) second else first
                add(Triple(first, second, expected))
            }
        }
        table.forEach { (first, second, expected) ->
            val kit = PaymentLedgerKit()
            val order = kit.paid()
            flag(kit, order.id, first)

            flag(kit, order.id, second)

            assertEquals(expected, kit.order(order.id).reviewReason, "first=$first second=$second")
        }
    }

    @Test
    fun `a money reason that replaces a supply reason is written to the history and alerts again`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.FULFILMENT_UNRESOLVED)

        flag(kit, order.id, PaymentReviewReason.REFUND_MISMATCH)

        val flagged = kit.store.listEvents(order.id).filter { it.type == PaymentEventType.REVIEW_FLAGGED }.map { it.providerCode }
        assertEquals(listOf("FULFILMENT_UNRESOLVED", "REFUND_MISMATCH", "REPLACED_FULFILMENT_UNRESOLVED"), flagged)
        assertEquals(listOf(order.id to PaymentReviewReason.FULFILMENT_UNRESOLVED, order.id to PaymentReviewReason.REFUND_MISMATCH), kit.review.flagged.toList())
    }

    @Test
    fun `a refused reason leaves no history and no alert`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        flag(kit, order.id, PaymentReviewReason.AMOUNT_MISMATCH)
        val events = kit.store.listEvents(order.id)

        flag(kit, order.id, PaymentReviewReason.FULFILMENT_UNRESOLVED)
        flag(kit, order.id, PaymentReviewReason.CONFIRM_UNRESOLVED)

        assertEquals(events, kit.store.listEvents(order.id))
        assertEquals(1, kit.review.flagged.size)
        assertTrue(PaymentReviewReason.AMOUNT_MISMATCH.overrides(PaymentReviewReason.FULFILMENT_UNRESOLVED))
    }
}
