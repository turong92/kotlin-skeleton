package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentTaskTypes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.dao.ConcurrencyFailureException

/**
 * 워커 여러 대가 `jobs` 를 퍼 가는 동안 장부 트랜잭션들이 작업을 **계속 넣는다**(운영의 모양: 승인 · 환불 · 웹훅이 작업을 넣고 워커들이 집는다).
 * 단정: **작업을 넣는 장부 트랜잭션은 DB 교착의 희생자가 되지 않는다**(되돌려지면 결제 · 환불이 통째로 되돌려진다) — 그리고 넣은 작업은 하나도 잃지 않고 모두 처리된다.
 *
 * 워커의 폴링은 다르다. MySQL 에서는 `job-queue-jdbc` 의 알려진 선점 교착(진행 문서 「정해야 할 것」 2)으로 다른 Gradle 작업과 함께 돌 때 폴링이 가끔 되돌려진다 —
 * **운영의 워커는 그것을 삼키고 다음 주기에 다시 한다**(작업은 `PENDING` 으로 남는다). 이 시험도 똑같이 한다: 되돌려진 폴링은 세어 두고(`pollDeadlocks`, 닫을 때 `MEASURED` 로 찍힌다) 다음 폴링이 다시 집으며,
 * 끝에서 남은 작업을 모두 치워 **유실이 없음**을 본다. PostgreSQL 에서는 폴링 롤백이 0 번이라 그대로 단정한다.
 */
class MultiInstanceJobClaimDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()

    private object Noop : JobHandler {
        override val type = PaymentTaskTypes.ORDER_FULFIL
        override fun handle(job: Job) = Unit
    }

    @Test
    fun `ledger transactions that keep enqueuing are never the victim of a database deadlock while workers claim, and no task is lost`() {
        LedgerCluster().use { c ->
            val workers = c.instances.map { it.worker(listOf(Noop), JobQueueProperties(batchSize = 5, workerId = "claim-${it.name}")) }
            val enqueuersLeft = CountDownLatch(c.size * 2)
            val enqueueRollbacks = CopyOnWriteArrayList<Exception>()
            val pollAttempts = AtomicInteger()
            /**
             * 운영의 워커처럼: **교착으로** 되돌려진 폴링은 삼키고(세어 둔다) 다음 폴링에 다시 한다. 그 밖의 동시성 실패(락 대기 시간 초과 등)는 삼키지 않는다 — 시험이 실패한다.
             * 되돌려진 폴링은 -1(빈 폴링이 아니다), 아니면 처리한 작업 수.
             */
            fun poll(n: Int): Int {
                pollAttempts.incrementAndGet()
                return try {
                    workers[n].pollOnce()
                } catch (e: ConcurrencyFailureException) {
                    if (!isDeadlock(e)) throw e
                    c.instances[n].pollDeadlocks += e
                    -1
                }
            }

            val results = race(c.size * 3) { i ->
                val inst = c.instances[i % c.size]
                if (i / c.size == 0) { // 이 백엔드의 워커 스레드
                    var polls = 0
                    while (enqueuersLeft.count > 0) {
                        poll(i % c.size)
                        polls++
                    }
                    polls
                } else { // 이 백엔드의 장부 트랜잭션들이 작업을 넣는다
                    repeat(ENQUEUES) { k ->
                        try { inst.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, "claim-$i-$k")) } catch (e: ConcurrencyFailureException) { enqueueRollbacks += e }
                    }
                    enqueuersLeft.countDown()
                    ENQUEUES
                }
            }
            results.forEach { it.getOrThrow() }
            // 경주가 끝났다 - 남은 작업은 워커들이 (되돌려진 폴링은 다시 하며) 모두 치운다. 한 건도 잃지 않았다.
            c.instances.indices.forEach { n -> repeat(1000) { if (poll(n) == 0) return@forEach } }
            val attempts = pollAttempts.get()

            assertEquals(0, enqueueRollbacks.size, "a ledger transaction that enqueues was rolled back by the database: ${enqueueRollbacks.firstOrNull()}")
            val expected = (c.size * 2 * ENQUEUES).toLong()
            assertEquals(expected, PaymentDb.count("select count(*) from jobs where status = 'DONE'"), "every enqueued task was claimed and handled once")
            assertEquals(0L, PaymentDb.count("select count(*) from jobs where status <> 'DONE'"), "nothing is left behind")
            if (DbTestDatabase.vendor != "mysql") {
                assertEquals(0, c.pollDeadlocks, "a worker poll was rolled back by a database deadlock: ${c.instances.flatMap { it.pollDeadlocks }.firstOrNull()}")
            } else {
                // 알려진 MySQL 선점 교착은 폴링 일부를 되돌릴 뿐이다 - 절반이 되돌려지는 회귀(예: 선점이 늘 서로 부딪친다)는 잡는다
                assertTrue(c.pollDeadlocks <= attempts * MAX_POLL_ROLLBACK_RATIO, "too many worker polls were rolled back by a deadlock: ${c.pollDeadlocks} of $attempts (limit ${MAX_POLL_ROLLBACK_RATIO})")
            }
        }
    }

    private companion object {
        /** 이 실패의 원인 사슬에 DB 의 교착 신호가 있는가 — MySQL 오류 1213 · PostgreSQL SQLSTATE 40P01. 락 대기 시간 초과(MySQL 1205)는 교착이 아니다. */
        fun isDeadlock(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { it is java.sql.SQLException && (it.errorCode == 1213 || it.sqlState == "40P01") }

        /** 되돌려진 폴링 / 시도한 폴링의 상한 — 실측 MySQL 은 시도 약 190 번 중 0~2 번(1% 안팎, 네 번 잼; 시험 출력의 MEASURED 줄). 그 스무 배 넘게 넉넉히 잡되 절반이 되돌려지는 회귀는 잡는다. */
        const val MAX_POLL_ROLLBACK_RATIO = 0.25
        const val ENQUEUES = 150
    }
}
