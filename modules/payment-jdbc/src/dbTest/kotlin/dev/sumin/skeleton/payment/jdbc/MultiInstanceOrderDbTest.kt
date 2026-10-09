package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentOrderNotPayableException
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.WebhookReceipt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **여러 대 시험 — 주문 쪽**(B10, 스펙 §3 · §6.1). 같은 DB 를 보는 독립된 백엔드 셋([LedgerCluster]: 연결 풀 · 저장소 · 서비스 묶음 · 큐 워커 · 시계 · 지급 훅이 각자 것이다)이
 * 같은 일을 같은 순간에 한다. 한 프로세스 · 한 조립 안의 16스레드 시험은 "프로세스 안 상태(자바 락 · 메모리 맵)에 기대지 않는다"를 증명하지 못한다 — 여기서 이기는 것은 DB 의 락 · 유니크뿐이다.
 * 결제사는 바깥에 하나뿐이라 셋이 함께 본다(호출 수를 센다).
 */
class MultiInstanceOrderDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun jobs(type: String): Long = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to type)

    private fun paidWrites(c: LedgerCluster, orderId: String): Int =
        c.instances[0].store.listEvents(orderId).count { it.toStatus == PaymentOrderStatus.PAID.name && it.fromStatus != it.toStatus }

    @Test
    fun `three instances creating the same order with one idempotency key leave one order and one expiry task`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val results = c.raceAcross(4) { inst, _ -> inst.create("same-$round").order.id }

                assertTrue(results.all { it.second.isSuccess }, "round $round: " + results.mapNotNull { it.second.exceptionOrNull() })
                assertEquals(1, results.map { it.second.getOrThrow() }.toSet().size, "round $round: everyone got the same order")
                assertEquals(1, PaymentDb.count("select count(*) from payment_orders"), "round $round")
                assertEquals(1, jobs(PaymentTaskTypes.ORDER_EXPIRE), "round $round: the expiry task was enqueued once, with the winning insert")
                assertEquals(1, PaymentDb.count("select count(*) from payment_events where type = 'ORDER_CREATED'"), "round $round")
            }
        }
    }

    @Test
    fun `three instances confirming one order with one payment key ask the provider once and pay once - the fulfil task seen by every worker pays once`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                // 홀수 라운드: 확정 직후의 첫 지급 시도가 실패한다 → 지급은 작업(어느 백엔드의 워커가 집을지 모른다)이 한다
                if (round % 2 == 1) c.plantedPaidFailures.set(1)
                val order = c.createAuthenticated("k$round", "pay_$round")
                c.provider.duringConfirm = { Thread.sleep(60) } // 이긴 쪽이 결제사에 있는 동안 나머지가 선점 락에 줄을 선다

                val results = c.raceAcross(4) { inst, _ -> inst.orders.confirm("acc_1", order.id, "pay_$round", order.amount) }

                assertTrue(results.all { it.second.isSuccess }, "round $round: " + results.mapNotNull { it.second.exceptionOrNull() })
                assertEquals(1, c.provider.confirmCalls.size, "round $round: the provider was asked once")
                assertTrue(c.listenersPaid <= 1, "round $round: at most one fulfilment so far")
                // 이제 모든 백엔드의 워커가 같은 순간에 폴링하고, 지급 처리기도 셋이 동시에 돈다
                c.drainWorkers()
                race(c.size * 2) { i -> c.instances[i % c.size].fulfil.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_FULFIL, order.id, 1, 5)) }.forEach { it.getOrThrow() }

                assertEquals(PaymentOrderStatus.PAID, c.instances[1].order(order.id).status, "round $round")
                assertEquals(1, paidWrites(c, order.id), "round $round: PAID was written once")
                assertEquals(1, c.listenersPaid, "round $round: the app's onPaid ran exactly once, summed over all instances")
                assertEquals(1, c.instances[2].store.listEvents(order.id).count { it.type == dev.sumin.skeleton.payment.PaymentEventType.FULFILLED }, "round $round")
            }
        }
    }

    @Test
    fun `one payment key confirmed on two different orders from different instances sticks to one order and reaches the provider once`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val first = c.instances[0].create("a-$round").order
                val second = c.instances[1].create("b-$round").order
                c.provider.duringConfirm = { Thread.sleep(40) }

                val results = c.raceAcross(4) { inst, t ->
                    val target = if ((c.instances.indexOf(inst) + t) % 2 == 0) first else second
                    target.id to inst.orders.confirm("acc_1", target.id, "pay_shared_$round", target.amount)
                }

                val owners = PaymentDb.jdbc.queryForList("select id from payment_orders where provider_payment_id = :p", mapOf("p" to "pay_shared_$round"), String::class.java)
                assertEquals(1, owners.size, "round $round: exactly one order holds the payment key")
                val winner = owners.single()
                assertEquals(1, c.provider.confirmCalls.size, "round $round: the provider was asked once")
                results.forEach { (inst, result) ->
                    val target = result.getOrNull()?.first
                    if (result.isSuccess) assertEquals(winner, target, "round $round: ${inst.name} succeeded on the order that does not own the key")
                    else assertTrue(result.exceptionOrNull() is PaymentOrderNotPayableException, "round $round: ${result.exceptionOrNull()}")
                }
                val loser = listOf(first, second).single { it.id != winner }
                assertEquals(PaymentOrderStatus.PENDING, c.instances[2].order(loser.id).status, "round $round: the order without the key was never touched")
                assertNull(c.instances[2].order(loser.id).providerPaymentId)
            }
        }
    }

    @Test
    fun `a confirm on one instance and the expiry task on other instances at the deadline end in one state and never deadlock`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS_RARE) { round ->
                val order = c.instances[0].create("k$round").order // 결제창 인증 전 — 결제사는 이 주문을 모른다(만료가 이기면 "결제 없음"으로 닫힌다)
                c.setClocks(order.checkoutExpiresAt) // 마감 시각 그대로: 승인도 만료도 아직 허용된다
                c.instances.forEach { it.queueSkew = java.time.Duration.ofMinutes(31) } // 만료 작업이 이미 때가 됐다
                c.provider.duringConfirm = { Thread.sleep(15) }

                // 백엔드마다: 0 번 스레드는 승인, 1 번 스레드는 그 백엔드의 큐 워커(만료 작업을 집는다)
                val results = c.raceAcross(2) { inst, t ->
                    if (t == 0) inst.orders.confirm("acc_1", order.id, "pay_$round", order.amount) else inst.poll()
                }
                c.drainWorkers()

                val final = c.instances[0].order(order.id)
                val confirms = results.filterIndexed { i, _ -> i / c.size == 0 }.map { it.second }
                when (final.status) {
                    PaymentOrderStatus.PAID -> {
                        assertTrue(confirms.all { it.isSuccess }, "round $round: ${confirms.mapNotNull { it.exceptionOrNull() }}")
                        assertEquals(1, c.provider.confirmCalls.size, "round $round: paid means the provider was asked once")
                        assertEquals(1, paidWrites(c, order.id), "round $round: PAID was written once")
                        assertEquals(1, c.listenersPaid, "round $round: one fulfilment")
                    }
                    PaymentOrderStatus.EXPIRED -> {
                        assertTrue(confirms.all { it.exceptionOrNull() is PaymentOrderNotPayableException }, "round $round: ${confirms.map { it.exceptionOrNull() }}")
                        assertEquals(0, c.provider.confirmCalls.size, "round $round: an expired order was never sent to the provider")
                        assertEquals(0, c.listenersPaid, "round $round: nothing was fulfilled")
                    }
                    else -> error("round $round ended as ${final.status}")
                }
                assertTrue(results.filterIndexed { i, _ -> i / c.size == 1 }.all { it.second.isSuccess }, "round $round: the workers finished instead of failing")
            }
        }
    }

    @Test
    fun `a confirm held at the provider on one instance while another instance receives and processes the webhook of that payment pays once`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS_RARE) { round ->
                val order = c.createAuthenticated("k$round", "pay_$round")
                val holding = CountDownLatch(1)
                val release = CountDownLatch(1)
                // 첫 승인 응답을 붙잡는다 — 그 사이 결제사는 결제를 끝냈고, 다른 백엔드들이 웹훅을 받아 처리한다
                c.provider.duringConfirm = {
                    holding.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "the held confirm was never released" }
                }

                val results = race(3) { n ->
                    when (n) {
                        0 -> c.instances[0].orders.confirm("acc_1", order.id, "pay_$round", order.amount).status
                        else -> {
                            check(holding.await(30, TimeUnit.SECONDS))
                            val inst = c.instances[n]
                            if (n == 1) c.provider.settle("pay_$round") // 결제사는 끝냈다 — 응답은 아직 붙잡혀 있다
                            val receipt = inst.deliver(c.provider.webhookDelivery("evt-$round-$n", "pay_$round", order.id))
                            inst.pollUntilIdle()
                            if (n == 2) { // 같은 배달이 다시 오고, 다른 배달도 온다
                                inst.deliver(c.provider.webhookDelivery("evt-$round-1", "pay_$round", order.id))
                                inst.pollUntilIdle()
                            }
                            if (n == 1) release.countDown()
                            receipt
                        }
                    }
                }.map { it.getOrThrow() }

                assertEquals(PaymentOrderStatus.PAID, results[0], "round $round: the held confirm came back after the webhook already settled the order")
                assertTrue(results.drop(1).all { it == WebhookReceipt.STORED || it == WebhookReceipt.DUPLICATE }, "round $round: $results")
                c.drainWorkers()
                assertEquals(PaymentOrderStatus.PAID, c.instances[0].order(order.id).status)
                assertEquals(1, paidWrites(c, order.id), "round $round: PAID was written once")
                assertEquals(1, c.listenersPaid, "round $round: one fulfilment")
                assertEquals(1, c.provider.confirmCalls.size, "round $round")
                assertTrue(
                    c.instances[0].store.listWebhookEvents(null, null, 10).all { it.status == PaymentWebhookStatus.PROCESSED },
                    "round $round: both webhook lines were processed",
                )
            }
        }
    }

    private companion object {
        const val ROUNDS = 30
        const val ROUNDS_RARE = 50
    }
}
