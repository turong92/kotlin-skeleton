package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FulfillmentTest {
    @Test
    fun `a listener that throws does not undo PAID and is retried`() {
        val kit = PaymentLedgerKit()
        kit.listener.failPaid(2) // 요청 스레드의 시도 + 작업의 첫 시도
        val created = kit.createAuthenticated()

        val result = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)

        assertEquals(PaymentOrderStatus.PAID, result.status, "the request still succeeds")
        assertNull(result.fulfilledAt)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        val task = kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).single()
        assertEquals(State.PENDING, task.state, "the first task attempt failed too and waits for the next")

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        assertEquals(State.DONE, task.state)
        assertEquals(1, kit.listener.paid.size)
        assertNotNull(kit.order(created.id).fulfilledAt)
    }

    @Test
    fun `a listener that keeps failing kills the job after its attempts but the order stays paid`() {
        val kit = PaymentLedgerKit()
        kit.listener.failPaid(100)
        val created = kit.createAuthenticated()
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)

        repeat(6) { kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL)) }

        assertEquals(1, kit.tasks.dead(PaymentTaskTypes.ORDER_FULFIL).size)
        assertEquals(PaymentOrderStatus.PAID, kit.order(created.id).status)
    }

    @Test
    fun `two workers fulfil one order exactly once`() {
        val kit = PaymentLedgerKit()
        kit.listener.failPaid(1) // 확정 직후의 시도는 실패해 fulfilled_at 이 빈 채 남는다
        val created = kit.createAuthenticated()
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        val ran = try {
            val futures = (1..16).map { pool.submit<Boolean> { start.await(); kit.fulfillment.fulfil(created.id) } }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, ran.count { it })
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `an order that is not paid is not fulfilled`() {
        val kit = PaymentLedgerKit()

        assertFalse(kit.fulfillment.fulfil(kit.create().order.id))
        assertEquals(0, kit.listener.attempts.get())
    }
}
