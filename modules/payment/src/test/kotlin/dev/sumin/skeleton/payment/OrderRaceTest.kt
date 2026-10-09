package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §3 18 — 동시 요청(메모리 대역판; 진짜 DB 의 경합은 `payment-jdbc` 의 dbTest).
 * 모든 전이는 주문 줄을 잠근 뒤 **상태를 다시 읽고** 판단한다 — "락 밖에서 읽은 값"으로 판단했다면 아래에서 돈이 어긋난다.
 */
class OrderRaceTest {
    private val ttl = Duration.ofMinutes(30)

    @Test
    fun `a confirm that lands between the expiry's lookup and its lock is not undone by the expiry - nothing was authenticated yet`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.advance(ttl) // 마감 시각 그대로: 승인도 만료도 아직 허용된다
        kit.gateway.afterLookup = {
            kit.gateway.afterLookup = null
            kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)
        }

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(1, kit.gateway.confirmCalls.size)
        assertEquals(1, kit.listener.paid.size)
        assertTrue(kit.store.listEvents(order.id).none { it.type == PaymentEventType.EXPIRED })
        assertEquals(PaymentTaskTypes.ORDER_EXPIRE, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().task.type)
        assertEquals(0, kit.tasks.dead().size)
    }

    @Test
    fun `a confirm that lands between the expiry's lookup and its lock is not undone by the expiry - the payment was in progress`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.advance(ttl)
        kit.gateway.afterLookup = {
            kit.gateway.afterLookup = null
            kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)
        }

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(0, kit.tasks.dead().size, "the expiry finished instead of retrying")
    }

    @Test
    fun `an approval response racing the reconcile job settles the order once`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.duringConfirm = {
            kit.gateway.duringConfirm = null
            kit.gateway.settle("pay_1") // 결제사는 이미 끝냈다
            kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, order.id, 1, 12)) // 조회 작업이 먼저 맞춘다
        }

        val result = kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)

        assertEquals(PaymentOrderStatus.PAID, result.status)
        val paidEvents = kit.store.listEvents(order.id).count { it.toStatus == PaymentOrderStatus.PAID.name && it.fromStatus != it.toStatus }
        assertEquals(1, paidEvents, "PAID was written once")
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_FULFIL).size, "one fulfil task")
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `an expiry thread and a confirm thread racing at the deadline always end in one consistent state`() {
        repeat(40) { round ->
            val kit = PaymentLedgerKit()
            val order = kit.create(key = "k$round").order
            kit.advance(ttl)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val confirm = pool.submit<Result<PaymentOrder>> {
                    start.await()
                    runCatching { kit.orders.confirm("acc_1", order.id, "pay_1", order.amount) }
                }
                val expire = pool.submit {
                    start.await()
                    kit.expire.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12))
                }
                start.countDown()
                val confirmed = confirm.get(10, TimeUnit.SECONDS)
                expire.get(10, TimeUnit.SECONDS)

                val final = kit.order(order.id)
                when (final.status) {
                    PaymentOrderStatus.PAID -> {
                        assertTrue(confirmed.isSuccess, "round $round")
                        assertEquals(1, kit.gateway.confirmCalls.size, "round $round: paid means the provider was asked once")
                        assertEquals(1, kit.listener.paid.size)
                    }
                    PaymentOrderStatus.EXPIRED -> {
                        assertTrue(confirmed.exceptionOrNull() is PaymentOrderNotPayableException, "round $round: ${confirmed.exceptionOrNull()}")
                        assertEquals(0, kit.gateway.confirmCalls.size, "round $round: an expired order was never sent to the provider")
                    }
                    else -> error("round $round ended as ${final.status}")
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
