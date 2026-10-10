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

        val result = converge(kit, order.id, snapshot())

        assertTrue(result.changed)
        val paid = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(3000, paid.paidAmount)
        assertEquals(PAID_AT, paid.paidAt)
        assertEquals("CARD", paid.method)
        assertEquals("HYUNDAI", paid.cardIssuer)
        assertEquals("1234", paid.cardLast4)
        assertEquals(1, kit.tasks.pending(PaymentTaskTypes.ORDER_FULFIL).size)
    }

    @Test
    fun `converging twice with the same snapshot changes nothing the second time`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)
        converge(kit, order.id, snapshot())
        val rowAfterFirst = kit.order(order.id)
        val eventsAfterFirst = kit.store.listEvents(order.id)
        val tasksAfterFirst = kit.tasks.all().size
        kit.advance(Duration.ofMinutes(5))

        val second = converge(kit, order.id, snapshot())

        assertTrue(!second.changed)
        assertEquals(rowAfterFirst, kit.order(order.id), "not even updated_at moved")
        assertEquals(eventsAfterFirst, kit.store.listEvents(order.id))
        assertEquals(tasksAfterFirst, kit.tasks.all().size)
    }

    @Test
    fun `converging twice with a mismatching snapshot flags it once and alerts once`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)
        val wrong = snapshot(total = PaymentAmount(2000, "KRW"))

        converge(kit, order.id, wrong)
        val flaggedRow = kit.order(order.id)
        kit.advance(Duration.ofMinutes(1))
        val second = converge(kit, order.id, wrong)

        assertTrue(!second.changed)
        assertEquals(flaggedRow, kit.order(order.id))
        assertEquals(1, kit.review.flagged.size)
        assertEquals(1, kit.store.listEvents(order.id).count { it.type == PaymentEventType.REVIEW_FLAGGED })
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
            assertNull(after.cardIssuer)
            assertEquals(0, kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).size)
            assertEquals(listOf(order.id to reason), kit.review.flagged)
        }
    }

    @Test
    fun `a mode that differs from the row's changes nothing but the review reason`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)

        converge(kit, order.id, snapshot(), mode = PaymentMode.LIVE)

        val after = kit.order(order.id)
        assertEquals(PaymentOrderStatus.CONFIRMING, after.status)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, after.reviewReason)
        assertEquals(order.copy(reviewReason = PaymentReviewReason.MODE_MISMATCH, updatedAt = after.updatedAt), after)
    }

    @Test
    fun `the card columns are filled when the order becomes paid but never written onto a row of an erased account`() {
        val kit = PaymentLedgerKit()
        val erased = confirming(kit, accountErased = true)

        converge(kit, erased.id, snapshot())

        val paid = kit.order(erased.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertNull(paid.cardIssuer)
        assertNull(paid.cardLast4)
        assertEquals("CARD", paid.method)
    }

    @Test
    fun `an issuer that is not a code is not stored - free text never reaches the ledger`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)

        converge(kit, order.id, snapshot(card = PaymentCard("Hyundai Card (Kim)", "12*4")))

        val paid = kit.order(order.id)
        assertNull(paid.cardIssuer)
        assertEquals("12*4", paid.cardLast4)
    }

    @Test
    fun `a method is stored only as a kind code - a label or a person's name never reaches the ledger`() {
        listOf(
            "CARD" to "CARD",
            "EASY_PAY" to "EASY_PAY",
            "카드" to null,
            "Kim Minsu" to null,
            "Kim-Minsu" to null,
            "card" to null,
            "<script>" to null,
            "A".repeat(41) to null,
            "01012345678" to null, // 숫자만 — 전화번호
            "1234567812345678" to null, // 숫자만 — 16자리 카드 번호
        ).forEach { (given, stored) ->
            val kit = PaymentLedgerKit()
            val order = confirming(kit)

            converge(kit, order.id, snapshot(method = given))

            assertEquals(stored, kit.order(order.id).method, given)
        }
    }

    @Test
    fun `a failed or expired snapshot fails a confirming order`() {
        listOf(ProviderPaymentStatus.FAILED to "PROVIDER_ABORTED", ProviderPaymentStatus.EXPIRED to "PROVIDER_EXPIRED").forEach { (status, code) ->
            val kit = PaymentLedgerKit()
            val order = confirming(kit)

            converge(kit, order.id, snapshot(status = status))

            val failed = kit.order(order.id)
            assertEquals(PaymentOrderStatus.FAILED, failed.status, status.name)
            assertEquals(code, failed.failureCode)
            assertEquals(kit.time.now(), failed.closedAt)
        }
    }

    @Test
    fun `a late failure after success keeps the order paid`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)
        converge(kit, order.id, snapshot())
        val paid = kit.order(order.id)

        listOf(ProviderPaymentStatus.FAILED, ProviderPaymentStatus.EXPIRED, ProviderPaymentStatus.IN_PROGRESS).forEach { late ->
            converge(kit, order.id, snapshot(status = late), source = ConvergeSource.EXPIRE)
        }

        assertEquals(paid, kit.order(order.id))
    }

    @Test
    fun `an in progress snapshot changes nothing`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)

        val result = converge(kit, order.id, snapshot(status = ProviderPaymentStatus.IN_PROGRESS))

        assertTrue(!result.changed)
        assertEquals(order, kit.order(order.id))
    }

    @Test
    fun `a pending order is closed by a failed snapshot only when the expiry asks`() {
        val kit = PaymentLedgerKit()
        val pending = kit.create().order

        converge(kit, pending.id, snapshot(status = ProviderPaymentStatus.EXPIRED), source = ConvergeSource.WEBHOOK)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(pending.id).status)

        converge(kit, pending.id, snapshot(status = ProviderPaymentStatus.EXPIRED), source = ConvergeSource.EXPIRE)
        val expired = kit.order(pending.id)
        assertEquals(PaymentOrderStatus.EXPIRED, expired.status)
        assertEquals("EXPIRED_UNPAID", expired.failureCode)
        assertEquals(PaymentEventType.EXPIRED, kit.store.listEvents(pending.id).last().type)
    }

    @Test
    fun `no payment at the provider expires a pending order on the expiry path and nothing else`() {
        val kit = PaymentLedgerKit()
        val pending = kit.create().order

        val ctx = { source: ConvergeSource -> ConvergeContext(PaymentMode.TEST, source) }
        kit.store.inTransaction { tx -> kit.convergence.convergeAbsent(tx, tx.lockOrder(pending.id)!!, ctx(ConvergeSource.WEBHOOK)) }
        assertEquals(PaymentOrderStatus.PENDING, kit.order(pending.id).status)

        kit.store.inTransaction { tx -> kit.convergence.convergeAbsent(tx, tx.lockOrder(pending.id)!!, ctx(ConvergeSource.EXPIRE)) }
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(pending.id).status)
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
            assertNull(reopened.failureCode)
            assertNull(reopened.closedAt)
            assertEquals(PaymentEventType.REOPENED_PAID, kit.store.listEvents(order.id).last().type)
            assertEquals(1, kit.tasks.pending(PaymentTaskTypes.ORDER_FULFIL).size)
        }
    }

    @Test
    fun `a partially refunded or refunded snapshot of an unpaid order still pays it first - the money did arrive`() {
        listOf(ProviderPaymentStatus.PARTIALLY_REFUNDED, ProviderPaymentStatus.REFUNDED).forEach { status ->
            val kit = PaymentLedgerKit()
            val order = confirming(kit)

            converge(kit, order.id, snapshot(status = status))

            assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status, status.name)
        }
    }

    @Test
    fun `a declined confirm fails a confirming order and keeps the provider code as an identifier`() {
        val kit = PaymentLedgerKit()
        val order = confirming(kit)
        val ctx = ConvergeContext(PaymentMode.TEST, ConvergeSource.CONFIRM)

        kit.store.inTransaction { tx -> kit.convergence.declined(tx, tx.lockOrder(order.id)!!, "reject card; DROP TABLE", ctx) }

        val failed = kit.order(order.id)
        assertEquals(PaymentOrderStatus.FAILED, failed.status)
        assertEquals("DECLINED", failed.failureCode, "a code that is not an identifier is replaced by ours")
    }

    companion object {
        private val PAID_AT = java.time.Instant.parse("2026-10-08T00:00:05Z")
    }
}
