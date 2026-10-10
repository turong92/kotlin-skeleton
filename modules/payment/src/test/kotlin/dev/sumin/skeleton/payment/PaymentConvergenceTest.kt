package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `PaymentConvergence.converge` 한 곳이 결제사와 장부를 맞춘다(스펙 §6.1) — 규칙을 스냅샷 표로 하나씩 본다. */
class PaymentConvergenceTest {
    private val amount = PaymentAmount(3000, "KRW")

    private fun snapshot(
        status: ProviderPaymentStatus = ProviderPaymentStatus.PAID,
        total: PaymentAmount = amount,
        orderId: String? = null,
        paymentId: String = "pay_1",
        card: PaymentCard? = PaymentCard("HYUNDAI", "1234"),
        method: String? = "CARD",
    ) = PaymentSnapshot(
        providerPaymentId = paymentId,
        orderId = orderId,
        status = status,
        total = total,
        remaining = total,
        method = method,
        paidAt = PAID_AT,
        refunds = emptyList(),
        card = card,
    )

    private fun converge(
        kit: PaymentLedgerKit,
        orderId: String,
        snapshot: PaymentSnapshot,
        source: ConvergeSource = ConvergeSource.RECONCILE,
        mode: PaymentMode = PaymentMode.TEST,
    ): ConvergeResult = kit.store.inTransaction { tx ->
        kit.convergence.converge(tx, tx.lockOrder(orderId)!!, snapshot, ConvergeContext(mode, source))
    }

    private fun confirming(kit: PaymentLedgerKit, accountErased: Boolean = false): PaymentOrder {
        val created = kit.create().order
        kit.store.inTransaction { tx ->
            val locked = tx.lockOrder(created.id)!!
            tx.updateOrder(
                locked.copy(
                    status = PaymentOrderStatus.CONFIRMING,
                    providerPaymentId = "pay_1",
                    accountErasedAt = if (accountErased) kit.time.now() else null,
                ),
            )
        }
        return kit.order(created.id)
    }

    @Test
    fun `a paid snapshot with the order's own total pays the order and copies the facts, the card included`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)

        assertTrue(converge(kit, order.id, snapshot()).changed)

        val paid = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(3000, paid.paidAmount)
        assertEquals("HYUNDAI", paid.cardIssuer)
        assertEquals(1, kit.tasks.pending(PaymentTaskTypes.ORDER_FULFIL).size)
    }

    @Test
    fun `converging twice with the same snapshot, paying or mismatching, changes nothing the second time and flags once`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)
        converge(kit, order.id, snapshot())
        val rowAfterFirst = kit.order(order.id)
        val eventsAfterFirst = kit.store.listEvents(order.id)
        val tasksAfterFirst = kit.tasks.all().size
        kit.advance(Duration.ofMinutes(5))

        assertTrue(!converge(kit, order.id, snapshot()).changed)

        assertEquals(rowAfterFirst, kit.order(order.id), "not even updated_at moved")
        assertEquals(eventsAfterFirst, kit.store.listEvents(order.id))
        assertEquals(tasksAfterFirst, kit.tasks.all().size)

        val other = confirming(kit)
        val wrong = snapshot(total = PaymentAmount(2000, "KRW"))
        converge(kit, other.id, wrong)
        converge(kit, other.id, wrong)
        assertEquals(1, kit.review.flagged.size)
        assertEquals(1, kit.store.listEvents(other.id).count { it.type == PaymentEventType.REVIEW_FLAGGED })
    }

    @Test
    fun `a different total, a different currency or a different order id is never paid`() {
        listOf(
            snapshot(total = PaymentAmount(2999, "KRW")) to PaymentReviewReason.AMOUNT_MISMATCH,
            snapshot(total = PaymentAmount(3000, "USD")) to PaymentReviewReason.AMOUNT_MISMATCH,
            snapshot(orderId = "ord_someone_elses") to PaymentReviewReason.ORDER_MISMATCH,
            snapshot(paymentId = "pay_other") to PaymentReviewReason.ORDER_MISMATCH,
        ).forEach { (bad, reason) ->
            val kit = PaymentLedgerKit()
            val order = confirming(kit)

            converge(kit, order.id, bad)

            val after = kit.order(order.id)
            assertEquals(PaymentOrderStatus.CONFIRMING, after.status, reason.name)
            assertEquals(reason, after.reviewReason)
            assertNull(after.paidAt)
            assertEquals(0, kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).size)
        }
    }

    @Test
    fun `a mode that differs from the row's changes nothing but the review reason`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)

        converge(kit, order.id, snapshot(), mode = PaymentMode.LIVE)

        val after = kit.order(order.id)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, after.reviewReason)
        assertEquals(order.copy(reviewReason = PaymentReviewReason.MODE_MISMATCH, updatedAt = after.updatedAt), after)
    }

    @Test
    fun `the card columns are never written onto a row of an erased account`() {
        val kit = PaymentLedgerKit()
        val erased = confirming(kit, accountErased = true)

        converge(kit, erased.id, snapshot())

        val paid = kit.order(erased.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertNull(paid.cardIssuer)
        assertNull(paid.cardLast4)
    }

    @Test
    fun `free text never reaches the ledger - an issuer that is not a code and a method that is not a kind code are dropped`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)
        converge(kit, order.id, snapshot(card = PaymentCard("Hyundai Card (Kim)", "12*4")))
        assertNull(kit.order(order.id).cardIssuer)

        listOf("CARD" to "CARD", "EASY_PAY" to "EASY_PAY", "카드" to null, "Kim Minsu" to null, "card" to null, "<script>" to null, "A".repeat(41) to null, "01012345678" to null, "1234567812345678" to null)
            .forEach { (given, stored) ->
                val k = PaymentLedgerKit()
                val o = confirming(k)

                converge(k, o.id, snapshot(method = given))

                assertEquals(stored, k.order(o.id).method, given)
            }
    }

    @Test
    fun `a pending order is closed by a failed snapshot or by no payment only when the expiry asks`() {
        val kit = PaymentLedgerKit()
        val pending = kit.create().order
        val absent = kit.create(key = "k2").order

        converge(kit, pending.id, snapshot(status = ProviderPaymentStatus.EXPIRED), source = ConvergeSource.WEBHOOK)
        kit.store.inTransaction { tx -> kit.convergence.convergeAbsent(tx, tx.lockOrder(absent.id)!!, ConvergeContext(PaymentMode.TEST, ConvergeSource.WEBHOOK)) }
        assertEquals(PaymentOrderStatus.PENDING, kit.order(pending.id).status)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(absent.id).status)

        converge(kit, pending.id, snapshot(status = ProviderPaymentStatus.EXPIRED), source = ConvergeSource.EXPIRE)
        kit.store.inTransaction { tx -> kit.convergence.convergeAbsent(tx, tx.lockOrder(absent.id)!!, ConvergeContext(PaymentMode.TEST, ConvergeSource.EXPIRE)) }
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(pending.id).status)
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(absent.id).status)
    }

    @Test
    fun `a closed order is reopened to paid only by provider evidence and says so in the history`() {
        listOf(PaymentOrderStatus.FAILED, PaymentOrderStatus.EXPIRED, PaymentOrderStatus.CANCELED).forEach { closed ->
            val kit = PaymentLedgerKit()
            val order = confirming(kit)
            kit.store.inTransaction { tx ->
                tx.updateOrder(tx.lockOrder(order.id)!!.copy(status = closed, failureCode = "X", closedAt = kit.time.now()))
            }

            converge(kit, order.id, snapshot())

            val reopened = kit.order(order.id)
            assertEquals(PaymentOrderStatus.PAID, reopened.status, closed.name)
            assertNull(reopened.closedAt)
            assertEquals(PaymentEventType.REOPENED_PAID, kit.store.listEvents(order.id).last().type)
            assertEquals(1, kit.tasks.pending(PaymentTaskTypes.ORDER_FULFIL).size)
        }
    }

    companion object {
        private val PAID_AT = java.time.Instant.parse("2026-10-08T00:00:05Z")
    }
}
