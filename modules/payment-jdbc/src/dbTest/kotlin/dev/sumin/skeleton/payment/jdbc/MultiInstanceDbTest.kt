package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentEventType
import dev.sumin.skeleton.payment.PaymentOrderNotPayableException
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundAmountExceededException
import dev.sumin.skeleton.payment.PaymentRefundInProgressException
import dev.sumin.skeleton.payment.PaymentRefundNotAllowedException
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.WebhookReceipt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **여러 대 시험**(B10, 스펙 §3 · §6.1). 같은 DB 를 보는 독립된 백엔드 셋([LedgerCluster]: 연결 풀 · 저장소 · 서비스 묶음 · 큐 워커 · 시계 · 지급 훅이 각자 것이다)이 같은 일을 같은 순간에 한다.
 * 한 프로세스 · 한 조립 안의 16스레드 시험은 "프로세스 안 상태(자바 락 · 메모리 맵)에 기대지 않는다"를 증명하지 못한다 — 여기서 이기는 것은 DB 의 락 · 유니크뿐이다.
 * 결제사는 바깥에 하나뿐이라 셋이 함께 본다(호출 수를 센다). 주문 · 환불 · 웹훅 수신함 · 작업 워커 순.
 */
class MultiInstanceDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun jobs(type: String): Long = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to type)

    private fun paidWrites(c: LedgerCluster, orderId: String): Int =
        c.instances[0].store.listEvents(orderId).count { it.toStatus == PaymentOrderStatus.PAID.name && it.fromStatus != it.toStatus }

    // ---- 주문 ----

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
                c.drainWorkers()
                race(c.size * 2) { i -> c.instances[i % c.size].fulfil.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_FULFIL, order.id, 1, 5)) }.forEach { it.getOrThrow() }

                assertEquals(PaymentOrderStatus.PAID, c.instances[1].order(order.id).status, "round $round")
                assertEquals(1, paidWrites(c, order.id), "round $round: PAID was written once")
                assertEquals(1, c.listenersPaid, "round $round: the app's onPaid ran exactly once, summed over all instances")
                assertEquals(1, c.instances[2].store.listEvents(order.id).count { it.type == PaymentEventType.FULFILLED }, "round $round")
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

    // ---- 환불 ----

    @Test
    fun `the same direct refund pressed on three instances with one idempotency key leaves one line one provider call and one recovery`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val order = c.paid("k$round", "pay_$round")
                c.provider.duringRefund = { Thread.sleep(80) } // 이긴 쪽이 결제사에 있는 동안 나머지가 선점 락에 줄을 선다

                val results = c.raceAcross(4) { inst, _ -> inst.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("same-$round")) }

                assertTrue(results.all { it.second.isSuccess }, "round $round: " + results.mapNotNull { it.second.exceptionOrNull() })
                assertEquals(1, results.map { it.second.getOrThrow().refund.id }.toSet().size, "round $round: everyone got the same line")
                assertEquals(1, c.provider.refundCalls.size, "round $round: the provider was asked once")
                assertEquals(1, PaymentDb.count("select count(*) from payment_refunds"), "round $round")
                assertEquals(1, jobs(PaymentTaskTypes.REFUND_RECONCILE), "round $round: one settle job")
                // 정산 작업과 회수 작업을 모든 백엔드의 워커가 동시에 본다 — 회수(onRefunded)는 합쳐서 한 번이다
                c.drainWorkers()
                val lineId = results.first().second.getOrThrow().refund.id
                race(c.size * 2) { i ->
                    c.instances[i % c.size].applier.tryApply(lineId)
                    c.instances[i % c.size].refundApply.handle(PaymentTaskRun(PaymentTaskTypes.REFUND_APPLY, lineId, 1, 5))
                }.forEach { it.getOrThrow() }
                assertEquals(1, c.listenersRefunded, "round $round: the app's refund hook ran once, summed over all instances")
                assertEquals(1000, c.instances[1].order(order.id).refundedAmount, "round $round")
                assertEquals(1000, c.provider.payment("pay_$round")!!.refundedTotal, "round $round")
            }
        }
    }

    @Test
    fun `two operators on different instances refunding the whole order with different keys send one refund and never exceed the paid amount`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val order = c.paid("k$round", "pay_$round")
                c.provider.duringRefund = { Thread.sleep(80) }

                // 백엔드마다 운영자 한 명(자기 키), 같은 운영자의 연타는 같은 키
                val results = c.raceAcross(4) { inst, _ ->
                    val n = c.instances.indexOf(inst)
                    inst.refunds.refund("op_$n", order.id, "OPERATOR", null, IdempotencyKey("whole-$n-$round"))
                }

                val ok = results.filter { it.second.isSuccess }
                val failed = results.mapNotNull { it.second.exceptionOrNull() }
                assertEquals(1, ok.map { it.second.getOrThrow().refund.id }.toSet().size, "round $round: one refund line won")
                assertEquals(1, ok.map { it.first }.toSet().size, "round $round: all successes came from the winner's instance")
                assertTrue(
                    failed.all { it is PaymentRefundInProgressException || it is PaymentRefundNotAllowedException || it is PaymentRefundAmountExceededException },
                    "round $round: $failed",
                )
                assertEquals(1, c.provider.refundCalls.size, "round $round: the provider was asked once")
                val lines = c.instances[2].store.listRefunds(order.id)
                assertEquals(1, lines.count { it.status == PaymentRefundStatus.SUCCEEDED }, "round $round")
                val after = c.instances[2].order(order.id)
                assertTrue(after.refundedAmount <= 3000, "round $round: ${after.refundedAmount}")
                assertEquals(lines.filter { it.status == PaymentRefundStatus.SUCCEEDED }.sumOf { it.amount.amount }, after.refundedAmount, "round $round")
                assertEquals(after.refundedAmount, c.provider.payment("pay_$round")!!.refundedTotal, "round $round: the provider and the ledger agree")
            }
        }
    }

    // ---- 웹훅 수신함 ----

    @Test
    fun `the same webhook delivery arriving on three instances leaves one inbox line and one task and settles the order once`() {
        LedgerCluster().use { c ->
            c.rounds(WEBHOOK_ROUNDS) { round ->
                val order = c.createAuthenticated("k$round", "pay_$round")
                c.provider.settle("pay_$round") // 결제사는 끝냈고, 웹훅이 그 사실을 알린다
                val delivery = c.provider.webhookDelivery("evt-$round", "pay_$round", order.id)

                val receipts = c.raceAcross(4) { inst, _ -> inst.deliver(delivery) }.map { it.second.getOrThrow() }

                assertEquals(1, receipts.count { it == WebhookReceipt.STORED }, "round $round: $receipts")
                assertEquals(receipts.size - 1, receipts.count { it == WebhookReceipt.DUPLICATE }, "round $round: $receipts")
                assertEquals(1, c.instances[0].store.listWebhookEvents(null, null, 10).size, "round $round: one inbox line")
                assertEquals(1, jobs(PaymentTaskTypes.WEBHOOK), "round $round: one processing task")

                // 모든 백엔드의 워커가 동시에 폴링한다 — 결제사 조회는 작업 수(1)만큼
                c.drainWorkers()
                assertEquals(1, c.provider.lookupCalls.size, "round $round: one lookup per task")
                assertEquals(PaymentOrderStatus.PAID, c.instances[2].order(order.id).status)
                assertEquals(1, paidWrites(c, order.id), "round $round: PAID was written once")
                assertEquals(1, c.listenersPaid, "round $round: one fulfilment")

                // 처리가 끝난 뒤의 재전송은 작업을 더 넣지 않는다
                c.raceAcross(2) { inst, _ -> inst.deliver(delivery) }.forEach { assertEquals(WebhookReceipt.DUPLICATE, it.second.getOrThrow(), "round $round") }
                assertEquals(1, jobs(PaymentTaskTypes.WEBHOOK), "round $round")
            }
        }
    }

    @Test
    fun `new event keys for one order split over three instances stop exactly at the per-order limit - the count is taken under the order lock`() {
        LedgerCluster().use { c ->
            c.rounds(ROUNDS_LIMIT) { round ->
                val capacity = c.properties.webhook.perOrderLimit.capacity
                val order = c.instances[0].create("k$round").order

                // 열여덟 스레드가 각자 새 이벤트 키 다섯 개를 연달아 보낸다 — 합 90 건, 상한(30)의 세 배
                val results = c.raceAcross(PER_INSTANCE) { inst, t ->
                    (0 until KEYS_PER_THREAD).map { k -> inst.deliver(c.provider.webhookDelivery("evt-$round-${inst.name}-$t-$k", "pay_$round", order.id)) }
                }

                val receipts = results.flatMap { it.second.getOrThrow() }
                val stored = PaymentDb.count("select count(*) from payment_webhook_events where resource_type = 'PAYMENT'").toInt()
                assertEquals(c.size * PER_INSTANCE * KEYS_PER_THREAD, receipts.size)
                assertEquals(capacity, stored, "round $round: exactly the limit - not a line more or less")
                assertEquals(stored, receipts.count { it == WebhookReceipt.STORED }, "round $round: every STORED receipt is a line")
                assertEquals(receipts.size - stored, receipts.count { it == WebhookReceipt.LIMITED }, "round $round: the rest were limited")
                assertEquals(1L, PaymentDb.count("select count(*) from payment_webhook_events where resource_type = 'LIMIT_MARKER'"), "round $round: one marker for the order and window")
                assertEquals(stored + 1L, jobs(PaymentTaskTypes.WEBHOOK), "round $round: one task per stored line plus the deferred one of the marker")
            }
        }
    }

    // ---- 작업 워커 ----

    private class CountingHandler(override val type: String, val workerName: String, val runs: ConcurrentHashMap<Long, CopyOnWriteArrayList<String>>, val inFlight: ConcurrentHashMap<Long, Int>) : JobHandler {
        override fun handle(job: Job) {
            // 같은 작업이 동시에 둘 돌면 여기서 2 가 된다
            val concurrent = inFlight.merge(job.id, 1, Int::plus)!!
            runs.computeIfAbsent(job.id) { CopyOnWriteArrayList() } += workerName + (if (concurrent > 1) "!concurrent" else "")
            Thread.sleep(3)
            inFlight.merge(job.id, -1, Int::plus)
        }
    }

    @Test
    fun `workers of three instances polling one jobs table run every task exactly once and never take the same task at the same time`() {
        LedgerCluster().use { c ->
            c.rounds(WORKER_ROUNDS) { round ->
                val types = listOf(PaymentTaskTypes.ORDER_RECONCILE, PaymentTaskTypes.REFUND_RECONCILE, PaymentTaskTypes.ORDER_FULFIL)
                repeat(TASKS) { i -> c.instances[i % c.size].enqueue(PaymentTask(types[i % types.size], "subject-$round-$i")) }

                val runs = ConcurrentHashMap<Long, CopyOnWriteArrayList<String>>()
                val inFlight = ConcurrentHashMap<Long, Int>()
                val workers = c.instances.map { inst ->
                    inst.worker(types.map { CountingHandler(it, inst.name, runs, inFlight) }, JobQueueProperties(batchSize = 4, workerId = "worker-${inst.name}"))
                }

                race(c.size) { n -> pollUntilIdle(c.instances[n].pollDeadlocks) { workers[n].pollOnce() } }.forEach { it.getOrThrow() }

                assertEquals(TASKS, runs.size, "round $round: every task ran")
                runs.forEach { (id, who) -> assertEquals(1, who.size, "round $round: job $id ran ${who.size} times: $who") }
                assertTrue(runs.values.none { run -> run.any { it.endsWith("!concurrent") } }, "round $round: two workers held one task at once")
                assertEquals(TASKS.toLong(), PaymentDb.count("select count(*) from jobs where status = 'DONE'"), "round $round")
            }
        }
    }

    private companion object {
        // 라운드 수: 로컬 · push CI 는 1/5(최소 3), nightly 는 `-Dskeleton.test.stress=full` 로 전부 (build.gradle.kts 가 시험 JVM 에 넘긴다)
        private val full = System.getProperty("skeleton.test.stress") == "full"
        private fun rounds(n: Int) = if (full) n else maxOf(3, n / 5)
        val ROUNDS = rounds(30)
        val ROUNDS_RARE = rounds(50)
        val WEBHOOK_ROUNDS = rounds(25)
        val ROUNDS_LIMIT = rounds(20)
        const val PER_INSTANCE = 6
        const val KEYS_PER_THREAD = 5
        val WORKER_ROUNDS = rounds(15)
        const val TASKS = 60
    }
}
