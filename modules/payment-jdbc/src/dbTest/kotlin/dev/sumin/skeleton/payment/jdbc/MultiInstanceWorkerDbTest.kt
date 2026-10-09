package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * **여러 대 시험 — 작업 워커**(B10). 백엔드마다 진짜 큐 워커가 있고, 같은 `jobs` 표를 동시에 퍼 간다: 각 작업은 정확히 한 번 실행되고(`FOR UPDATE SKIP LOCKED`) 두 워커가 같은 작업을 동시에 집지 않는다.
 * 처리기는 수를 세는 대역이다(처리기 안의 일은 다른 시험이 한다) — 여기서 재는 것은 큐 쪽의 "한 번"이다.
 */
class MultiInstanceWorkerDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

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
        val participation = mutableListOf<Map<String, Int>>()
        LedgerCluster().use { c ->
            c.rounds(ROUNDS) { round ->
                val types = listOf(PaymentTaskTypes.ORDER_RECONCILE, PaymentTaskTypes.REFUND_RECONCILE, PaymentTaskTypes.ORDER_FULFIL)
                repeat(TASKS) { i -> c.instances[i % c.size].enqueue(PaymentTask(types[i % types.size], "subject-$round-$i")) }
                assertEquals(TASKS.toLong(), PaymentDb.count("select count(*) from jobs where status = 'PENDING'"))

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
                participation += runs.values.flatten().groupingBy { it }.eachCount()
            }
        }
        println("MEASURED worker participation (${DbTestDatabase.vendor}): tasks per worker per round=$participation")
        assertTrue(participation.any { it.size >= 2 }, "no round had more than one worker doing work - the race never happened")
    }

    @Test
    fun `the instances share no object - separate pools, stores, services, workers, clocks and hooks - so a half-done write on one is invisible to the other until it commits`() {
        LedgerCluster().use { c ->
            // 객체 동일성은 클러스터가 만들 때 단정한다(check). 여기서는 눈에 보이는 결과 하나: 한 백엔드의 커밋 전 쓰기는 다른 백엔드에 보이지 않는다(연결이 다르다)
            val a = c.instances[0]
            val b = c.instances[1]
            assertNotSame(a.store, b.store)
            assertNotSame(a.dataSource, b.dataSource)
            assertNotSame(a.orders, b.orders)
            val order = PaymentTestOrders.order(id = "ord_" + "b".repeat(32), dedupeKey = "dedupe-independent")

            val seenInside = a.store.inTransaction { tx ->
                assertTrue(tx.insertOrderIfAbsent(order))
                b.store.findOrder(order.id)
            }

            assertEquals(null, seenInside, "the other instance must not see an uncommitted row")
            assertEquals(order.id, b.store.findOrder(order.id)?.id, "after the commit it does")
        }
    }

    private companion object {
        const val ROUNDS = 15
        const val TASKS = 60
    }
}
