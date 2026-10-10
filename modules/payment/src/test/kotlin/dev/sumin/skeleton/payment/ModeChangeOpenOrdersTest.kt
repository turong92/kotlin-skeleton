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
 * 결제 모드가 바뀐 뒤(테스트 → 실결제) 옛 주문의 처우.
 * - 모드가 다른 `PENDING` 은 승인을 선점한 적이 없다(돈이 움직였을 수 없다) → 마감이 지나면 표시 · 경보 · 조회 없이 `EXPIRED`.
 * - 모드가 다른 `CONFIRMING` · 결제된 주문은 돈이 움직였을 수 있다 → `MODE_MISMATCH`. 끝난 주문 · `PENDING` 의 운영자 `sync` 는 표시 없이 409 만.
 * - 결제사가 받았다는 표시(`AMOUNT_MISMATCH` · `ORDER_MISMATCH`)가 있는 `PENDING` 은 모드가 달라도 닫지 않는다.
 */
class ModeChangeOpenOrdersTest {
    private val ttl = Duration.ofMinutes(30)
    private val op = "op_1"

    @Test
    fun `a pending order of another mode is retried before its deadline and expired without lookup or flag after it`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(ttl.minusSeconds(1))

        assertFailsWith<PaymentTaskRetryException> { kit.expire.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12)) }
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)

        kit.advance(Duration.ofSeconds(1))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertNull(kit.order(order.id).reviewReason)
        assertEquals(0, kit.gateway.lookupCalls.size)
        assertTrue(kit.review.flagged.isEmpty())
        assertEquals(State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state)
    }

    @Test
    fun `a confirming order of another mode is still flagged as a mode mismatch - money may have moved`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(Duration.ofHours(1))

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(created.id).reviewReason)
    }

    @Test
    fun `sync of another mode answers mode mismatch - it flags a confirming or paid order and leaves a closed or pending one untouched`() {
        val kit = PaymentLedgerKit()
        val closed = kit.create(key = "k0").order
        kit.advance(ttl)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        val pending = kit.create(key = "k1").order
        val confirming = kit.createAuthenticated(key = "k2", paymentKey = "pay_c")
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", confirming.id, "pay_c", confirming.amount)
        val paid = kit.paid(key = "k3", paymentKey = "pay_2")
        val eventsBefore = listOf(closed, pending).map { kit.store.listEvents(it.id) }
        kit.gateway.mode = PaymentMode.LIVE

        listOf(closed, pending, confirming, paid).forEach { assertFailsWith<PaymentModeMismatchException> { kit.refunds.sync(op, it.id) } }

        assertEquals(eventsBefore, listOf(closed, pending).map { kit.store.listEvents(it.id) }, "no history line, no flag")
        assertNull(kit.order(closed.id).reviewReason)
        assertNull(kit.order(pending.id).reviewReason)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(confirming.id).reviewReason)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(paid.id).reviewReason)
    }

    @Test
    fun `a pending order the provider once said it received is never closed by the expiry, in another mode too - the job dies for a person`() {
        listOf(PaymentReviewReason.AMOUNT_MISMATCH, PaymentReviewReason.ORDER_MISMATCH).forEach { reason ->
            val kit = PaymentLedgerKit()
            val order = kit.create().order
            kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(order.id)!!, reason, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE)) }
            kit.gateway.mode = PaymentMode.LIVE
            kit.advance(ttl)

            kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

            assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status, "$reason")
            assertEquals(reason, kit.order(order.id).reviewReason, "$reason")
            assertEquals(State.DEAD, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state, "$reason")
            assertEquals(0, kit.gateway.lookupCalls.size, "$reason")
        }
    }
}
