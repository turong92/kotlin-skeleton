package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 회수(`onRefunded`)와 지급 전 환불의 순서 결정을 고정한다. */
class RefundApplyTest {
    private val op = "op_1"

    private class ThrowingRefundListener(val inner: dev.sumin.skeleton.payment.testing.RecordingOrderListener) : PaymentOrderListener {
        var failures = 0
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun onPaid(order: PaymentOrder) {
            inner.onPaid(order)
            events += "paid"
        }

        override fun onRefunded(order: PaymentOrder, refund: PaymentRefund) {
            if (failures > 0) {
                failures--
                error("planted refund hook failure")
            }
            inner.onRefunded(order, refund)
            events += "refunded:${refund.amount.amount}"
        }
    }

    @Test
    fun `a refund hook that throws leaves the money state as it is and is retried by the apply job`() {
        val listener = dev.sumin.skeleton.payment.testing.RecordingOrderListener()
        val kit = PaymentLedgerKit(listener = listener)
        val throwing = ThrowingRefundListener(listener).also { it.failures = 2 }
        val order = kit.paid()
        // 같은 장부에서 리스너만 바꿔 끼운다
        val applier = PaymentRefundApplier(kit.store, throwing, kit.fulfillment, kit.writer)
        val refunds = PaymentRefunds(kit.store, kit.tasks, kit.router, kit.convergence, applier, kit.writer, kit.properties)
        val handler = RefundApplyTaskHandler(applier, kit.store, kit.convergence)

        val outcome = refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))

        assertEquals(PaymentRefundStatus.SUCCEEDED, outcome.refund.status, "the request still succeeds")
        assertEquals(1000, kit.order(order.id).refundedAmount)
        assertNull(kit.store.findRefund(outcome.refund.id)!!.appliedAt, "the inline try failed")

        val run = PaymentTaskRun(PaymentTaskTypes.REFUND_APPLY, outcome.refund.id, 1, 5)
        val failure = assertFailsWith<PaymentTaskRetryException> { handler.handle(run) }
        assertTrue(failure.message!!.contains(outcome.refund.id))
        assertTrue(!failure.message!!.contains("planted"), "the hook's words stay out of the job row")

        handler.handle(run)
        handler.handle(run)

        assertEquals(1, throwing.events.count { it.startsWith("refunded") }, "the hook ran once")
        assertNotNull(kit.store.findRefund(outcome.refund.id)!!.appliedAt)
    }

    @Test
    fun `sixteen workers applying one refund call the hook exactly once`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val line = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        // 확정 직후의 시도가 이미 회수했으니 줄을 되돌려 놓고 경주시킨다
        kit.store.inTransaction { tx -> tx.lockOrder(order.id); tx.updateRefund(tx.lockRefund(line.id)!!.copy(appliedAt = null)) }
        kit.listener.refunded.clear()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        val ran = try {
            val futures = (1..16).map { pool.submit<Boolean> { start.await(); kit.applier.apply(line.id) } }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, ran.count { it })
        assertEquals(1, kit.listener.refunded.size)
    }

    // --- 지급 전 환불의 순서 (B3 리뷰가 남긴 결정) ---

    /** 승인 응답을 못 받은 주문 — 결제사는 이미 결제를 끝냈다. 장부는 아직 CONFIRMING. */
    private fun confirmingButPaidAtProvider(kit: PaymentLedgerKit): PaymentOrder {
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        return kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
    }

    @Test
    fun `a full refund before fulfilment skips onPaid and still tells the app about the refund`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        assertEquals(PaymentOrderStatus.CONFIRMING, order.status)
        kit.gateway.consoleCancel("pay_1", 3000) // 돈이 돌아갔다 - 지급 전에
        kit.advance(Duration.ofSeconds(60))

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE)) // PAID 로 올라간 직후 같은 맞추기에서 전액 환불로 이어진다
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL, PaymentTaskTypes.REFUND_APPLY))

        val done = kit.order(order.id)
        assertEquals(PaymentOrderStatus.REFUNDED, done.status)
        assertEquals(0, kit.listener.paid.size, "nothing is handed out for money that went back")
        assertNull(done.fulfilledAt, "it was never fulfilled")
        assertEquals(1, kit.listener.refunded.size)
    }

    @Test
    fun `a partial refund before fulfilment grants first and then revokes - even when the apply job runs before the fulfil job`() {
        val kit = PaymentLedgerKit()
        kit.listener.failPaid(10) // 확정 직후의 지급도, 환불 직후의 회수 시도(먼저 지급을 시도한다)도 실패한다
        val order = kit.paid()
        val outcome = kit.refunds.refund(op, order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        assertNull(kit.order(order.id).fulfilledAt)
        assertNull(kit.store.findRefund(outcome.refund.id)!!.appliedAt)
        kit.listener.failPaid(0)

        kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY)) // 회수 작업이 지급 작업보다 먼저 돈다
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(listOf("paid", "refunded"), kit.listener.calls, "the hook for the refund never comes before the hook for the payment")
        assertNotNull(kit.order(order.id).fulfilledAt)
    }
}
