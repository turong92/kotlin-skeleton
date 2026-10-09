package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 결제 모드가 바뀐 뒤(테스트 → 실결제) 옛 주문의 처우 (R-BE 1회차 R-1).
 * - 모드가 다른 `PENDING` 은 승인을 선점한 적이 없다(돈이 움직였을 수 없다) → 마감이 지나면 표시 · 경보 · 조회 없이 `EXPIRED`.
 * - 모드가 다른 `CONFIRMING` 은 돈이 움직였을 수 있다 → 지금처럼 `MODE_MISMATCH`.
 * - 운영자 `sync` 는 끝난 주문(닫힌 상태 · 전액 환불)과 `PENDING` 에 표시를 찍지 않고 409 만 낸다(`PENDING` 은 선점한 적이 없다) — `CONFIRMING` · 결제된 주문에는 찍는다.
 * - 만료가 `PENDING` 을 `EXPIRED` 로 닫을 때 그 줄에 남아 있던 `MODE_MISMATCH` 는 비운다(R-BE 2회차 M-1).
 * - 결제사가 받았다는 표시(`AMOUNT_MISMATCH` · `ORDER_MISMATCH`)가 있는 `PENDING` 은 모드가 달라도 닫지 않는다(M-2).
 */
class ModeChangeOpenOrdersTest {
    private val ttl = Duration.ofMinutes(30)
    private val op = "op_1"

    @Test
    fun `a pending order of another mode past its deadline is expired - no lookup, no flag, no alert, the job is done`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        val after = kit.order(order.id)
        assertEquals(PaymentOrderStatus.EXPIRED, after.status)
        assertNull(after.reviewReason)
        assertEquals(0, kit.gateway.lookupCalls.size)
        assertTrue(kit.review.flagged.isEmpty())
        assertEquals(State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state)
        assertEquals(listOf(PaymentEventType.ORDER_CREATED, PaymentEventType.EXPIRED), kit.store.listEvents(order.id).map { it.type })
    }

    @Test
    fun `before its deadline a pending order of another mode is left alone and the job retries - after the deadline it closes without a flag`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(ttl.minusSeconds(1))

        assertFailsWith<PaymentTaskRetryException> { kit.expire.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12)) }
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertNull(kit.order(order.id).reviewReason)

        kit.advance(Duration.ofSeconds(1))
        kit.expire.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 2, 12))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertNull(kit.order(order.id).reviewReason)
        assertEquals(0, kit.gateway.lookupCalls.size)
        assertTrue(kit.review.flagged.isEmpty())
    }

    @Test
    fun `a confirming order of another mode is still flagged as a mode mismatch - money may have moved`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(Duration.ofHours(1))

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(created.id).reviewReason)
        assertEquals(listOf(created.id to PaymentReviewReason.MODE_MISMATCH), kit.review.flagged)
    }

    @Test
    fun `sync on a closed order of another mode answers mode mismatch but flags nothing and writes no event`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.advance(ttl)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        val events = kit.store.listEvents(order.id)
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, order.id) }

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(events, kit.store.listEvents(order.id))
        assertTrue(kit.review.flagged.isEmpty())
    }

    @Test
    fun `sync on a fully refunded order of another mode answers mode mismatch but flags nothing`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.consoleCancel("pay_1", 3000)
        kit.refunds.sync(op, order.id)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
        val events = kit.store.listEvents(order.id)
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, order.id) }

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(events, kit.store.listEvents(order.id))
    }

    @Test
    fun `sync on a pending order of another mode answers mode mismatch and flags nothing - it never claimed an approval`() {
        val kit = PaymentLedgerKit()
        val pending = kit.create(key = "k1").order
        val events = kit.store.listEvents(pending.id)
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, pending.id) }

        assertNull(kit.order(pending.id).reviewReason)
        assertEquals(events, kit.store.listEvents(pending.id))
        assertTrue(kit.review.flagged.isEmpty())
    }

    @Test
    fun `sync on a confirming or paid order of another mode still flags it`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated(key = "k1")
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        val paid = kit.paid(key = "k2", paymentKey = "pay_2")
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, created.id) }
        assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, paid.id) }

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(created.id).reviewReason)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(paid.id).reviewReason)
    }

    @Test
    fun `a mode mismatch mark that was already on a pending order is cleared when the expiry closes it`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        flag(kit, order.id, PaymentReviewReason.MODE_MISMATCH) // 이전 버전이 찍었거나 다른 길이 찍은 줄
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertNull(kit.order(order.id).reviewReason, "a closed order does not stay on the review list")
    }

    @Test
    fun `a pending order the provider once said it received is never closed by the expiry, in another mode too - the job dies for a person`() {
        listOf(PaymentReviewReason.AMOUNT_MISMATCH, PaymentReviewReason.ORDER_MISMATCH).forEach { reason ->
            val kit = PaymentLedgerKit()
            val order = kit.create().order
            flag(kit, order.id, reason)
            kit.gateway.mode = PaymentMode.LIVE
            kit.advance(ttl)

            kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

            assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status, "$reason")
            assertEquals(reason, kit.order(order.id).reviewReason, "$reason")
            assertEquals(State.DEAD, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state, "$reason")
            assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().attempts, "no futile retries: $reason")
            assertEquals(0, kit.gateway.lookupCalls.size, "$reason")
        }
    }

    private fun flag(kit: PaymentLedgerKit, orderId: String, reason: PaymentReviewReason) {
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(orderId)!!, reason, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE)) }
    }
}
