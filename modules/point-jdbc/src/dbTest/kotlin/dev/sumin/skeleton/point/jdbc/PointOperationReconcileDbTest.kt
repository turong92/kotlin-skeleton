package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.point.PointOperationKind
import dev.sumin.skeleton.point.PointOperationScheduler
import dev.sumin.skeleton.point.PointOperationStatus
import dev.sumin.skeleton.point.PointReceipt
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 불명 건 조회: 원장으로 가르고, 포기한 건도 줄이 나타나면 성공으로 바로잡고, 조회 작업은 영수증과 같은 트랜잭션에서 예약된다 */
class PointOperationReconcileDbTest {
    private val operations = PointDb.operations
    private val wallet = PointDb.wallet
    private val log = PointDb.log
    private val jdbc = PointDb.jdbc
    private val now: Instant = Instant.parse("2026-11-07T00:00:00Z")

    @BeforeTest
    fun clean() {
        listOf("point_spend_allocations", "point_restore_links", "point_operation_attempts", "point_operations", "point_entries", "point_balances")
            .forEach { jdbc.update("delete from $it", emptyMap<String, Any>()) }
        wallet.earn("a1", 100, null, "GRANT", "g1")
    }

    private fun pending(ref: String = "o1", amount: Long = 40) =
        log.accept(PointReceipt("a1", PointOperationKind.SPEND, amount, "ORDER", ref, "f".repeat(64), "svc:order"), now).operation.also { log.beginAttempt(it.operationId, now) }

    private fun job(payload: String) = Job(1, POINT_OPERATION_JOB_TYPE, payload, JobStatus.RUNNING, 1, 12, now, "w", now, null, now, now)

    @Test
    fun `resolve closes a processing operation whose ledger line exists and leaves one without a line unknown`() {
        val found = pending("o1", 40)
        wallet.spend("a1", 40, "ORDER", "o1")   // 바깥 트랜잭션이 늦게 커밋됐다
        val missing = pending("o2", 10)

        val closed = operations.resolve(found.operationId)!!
        val waiting = operations.resolve(missing.operationId)!!

        assertEquals(PointOperationStatus.SUCCEEDED, closed.status)
        assertEquals(wallet.entryOf("ORDER", "o1")!!.id, closed.resultEntryId)
        assertEquals(PointOperationStatus.UNKNOWN, waiting.status)
        assertEquals("POINT.UNKNOWN", waiting.resultCode)
        assertNull(wallet.entryOf("ORDER", "o2"))
    }

    @Test
    fun `a line of another kind or amount under the key does not close the operation as a success`() {
        val op = pending("o1", 40)
        wallet.spend("a1", 7, "ORDER", "o1")   // 키는 같지만 금액이 다르다 - 이 작업의 결과가 아니다

        assertEquals(PointOperationStatus.UNKNOWN, operations.resolve(op.operationId)!!.status)
    }

    @Test
    fun `an abandoned operation is corrected to a success when its ledger line shows up later, and a failure stays failed`() {
        val late = pending("o1", 40)
        operations.resolve(late.operationId)
        operations.abandon(late.operationId)
        assertEquals(PointOperationStatus.ABANDONED, log.find(late.operationId)!!.status)

        wallet.spend("a1", 40, "ORDER", "o1")
        val corrected = operations.resolve(late.operationId)!!

        assertEquals(PointOperationStatus.SUCCEEDED, corrected.status)
        assertEquals(wallet.entryOf("ORDER", "o1")!!.id, corrected.resultEntryId)

        val failed = operations.spend("svc:order", "a1", 5000, "ORDER", "big").operation
        operations.abandon(failed.operationId)
        assertEquals(PointOperationStatus.FAILED, operations.resolve(failed.operationId)!!.status)
    }

    @Test
    fun `resolving an unknown id is null and abandoning never reopens a success`() {
        assertNull(operations.resolve("op_nope"))
        val ok = operations.spend("svc:order", "a1", 10, "ORDER", "o1").operation
        operations.abandon(ok.operationId)
        assertEquals(PointOperationStatus.SUCCEEDED, log.find(ok.operationId)!!.status)
    }

    @Test
    fun `the receipt schedules one reconcile job after the first delay, only for a new operation, in the same transaction`() {
        val scheduled = mutableListOf<Pair<String, Instant>>()
        val scheduler = PointOperationScheduler { id, at -> scheduled += id to at }
        val withJobs = JdbcPointOperationLog(jdbc, PointDb.txManager, DbTestDatabase.dialect, scheduler = { scheduler }, firstDelay = Duration.ofSeconds(90))
        val receipt = PointReceipt("a1", PointOperationKind.SPEND, 40, "ORDER", "o1", "f".repeat(64), "svc:order")

        val first = withJobs.accept(receipt, now)
        withJobs.accept(receipt, now.plusSeconds(5))   // 같은 키 재시도의 영수증 - 새 작업이 아니다
        assertEquals(listOf(first.operation.operationId to now.plusSeconds(90)), scheduled)

        withJobs.beginAttempt(first.operation.operationId, now)   // 영수증을 만든 호출의 첫 시도 - 영수증 때 건 작업이 있다
        assertEquals(1, scheduled.size)
        withJobs.beginAttempt(first.operation.operationId, now.plusSeconds(10), reschedule = true)   // 같은 키 재시도 - 조회 작업을 다시 건다
        assertEquals(listOf(first.operation.operationId to now.plusSeconds(90), first.operation.operationId to now.plusSeconds(100)), scheduled)

        val broken = JdbcPointOperationLog(jdbc, PointDb.txManager, DbTestDatabase.dialect, scheduler = { PointOperationScheduler { _, _ -> error("queue down") } })
        assertFailsWith<IllegalStateException> { broken.accept(receipt.copy(sourceRef = "o2"), now) }
        assertNull(log.findBySource("ORDER", "o2"), "no receipt without its reconcile job")
    }

    @Test
    fun `the queue handler resolves from the payload, finishes when settled and asks for a retry while unresolved`() {
        val settled = operations.spend("svc:order", "a1", 10, "ORDER", "o1").operation
        val waiting = pending("o2", 10)
        val handler = PointOperationResolveHandler { operations }

        handler.handle(job("""{"operationId":"${settled.operationId}"}"""))   // 이미 성공 - 끝
        handler.handle(job("""{"operationId":"op_missing"}"""))   // 모르는 작업 - 끝(재시도할 것이 없다)
        assertFailsWith<IllegalStateException> { handler.handle(job("""{"operationId":"${waiting.operationId}"}""")) }   // 못 찾았다 - 큐가 백오프로 다시
        assertFailsWith<PermanentJobFailureException> { handler.handle(job("""{"nope":1}""")) }

        wallet.spend("a1", 10, "ORDER", "o2")
        handler.handle(job("""{"operationId":"${waiting.operationId}"}"""))
        assertEquals(PointOperationStatus.SUCCEEDED, log.find(waiting.operationId)!!.status)
    }

    @Test
    fun `a dead reconcile job abandons its operation, ignores other jobs, and never throws`() {
        val waiting = pending("o1", 10)
        val listener = PointOperationDeadListener { operations }

        listener.onDead(job("""{"operationId":"${waiting.operationId}"}""").copy(type = "other"), "x")
        assertEquals(PointOperationStatus.PROCESSING, log.find(waiting.operationId)!!.status)
        listener.onDead(job("""{"operationId":"${waiting.operationId}"}"""), "retries used up")
        listener.onDead(job("""not json"""), "x")

        assertEquals(PointOperationStatus.ABANDONED, log.find(waiting.operationId)!!.status)
        wallet.spend("a1", 10, "ORDER", "o1")
        assertTrue(operations.resolve(waiting.operationId)!!.status == PointOperationStatus.SUCCEEDED, "even after the queue gave up")
    }

    @Test
    fun `the queue scheduler enqueues the reconcile job with the configured attempts`() {
        val seen = mutableListOf<List<Any?>>()
        val queue = object : JobQueue {
            override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long {
                seen += listOf(type, payloadJson, runAt, maxAttempts)
                return 1
            }
        }

        JobQueuePointOperationScheduler(queue, 12).schedule("op_1", now)

        assertEquals(listOf<List<Any?>>(listOf(POINT_OPERATION_JOB_TYPE, """{"operationId":"op_1"}""", now, 12)), seen)
    }

    @Test
    fun `a retry after a call that died between the receipt and its first attempt re-arms the reconcile job`() {
        val scheduled = mutableListOf<String>()
        val scheduler = PointOperationScheduler { id, _ -> scheduled += id }
        val real = JdbcPointOperationLog(jdbc, PointDb.txManager, DbTestDatabase.dialect, scheduler = { scheduler })
        class DiesBeforeFirstAttempt(private val delegate: dev.sumin.skeleton.point.PointOperationLog) : dev.sumin.skeleton.point.PointOperationLog by delegate {
            override fun beginAttempt(operationId: String, now: Instant, reschedule: Boolean): Int = throw IllegalStateException("process died")
        }

        assertFailsWith<IllegalStateException> { JdbcPointOperations(PointDb.store, DiesBeforeFirstAttempt(real), PointDb.txManager, PointDb.time).spend("svc:order", "a1", 40, "ORDER", "o1") }
        val stuck = real.findBySource("ORDER", "o1")!!
        assertEquals(0, stuck.attempts, "died after the receipt, before any attempt")
        assertEquals(listOf(stuck.operationId), scheduled)

        val retry = JdbcPointOperations(PointDb.store, real, PointDb.txManager, PointDb.time).spend("svc:order", "a1", 40, "ORDER", "o1")

        assertTrue(retry.succeeded)
        assertEquals(1, retry.operation.attempts)
        assertEquals(listOf(stuck.operationId, stuck.operationId), scheduled, "the retry arms a second job - the first may be gone")
    }
}
