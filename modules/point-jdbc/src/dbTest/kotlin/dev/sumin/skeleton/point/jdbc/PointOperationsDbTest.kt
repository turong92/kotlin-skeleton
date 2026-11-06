package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.AcceptedOperation
import dev.sumin.skeleton.point.PointEntry
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointOperationLog
import dev.sumin.skeleton.point.PointOperationStatus
import dev.sumin.skeleton.point.PointReceipt
import dev.sumin.skeleton.point.PointSourceConflictException
import dev.sumin.skeleton.point.PointStore
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

/** 기록되는 포인트 쓰기(`spend` · `earn`): 영수증은 부수효과 전에, 성공 표시는 원장과 한 몸, ACK 손실은 불명 */
class PointOperationsDbTest {
    private val operations = PointDb.operations
    private val wallet = PointDb.wallet
    private val log = PointDb.log
    private val jdbc = PointDb.jdbc

    @BeforeTest
    fun clean() {
        listOf("point_spend_allocations", "point_restore_links", "point_operation_attempts", "point_operations", "point_entries", "point_balances")
            .forEach { jdbc.update("delete from $it", emptyMap<String, Any>()) }
        wallet.earn("a1", 100, null, "GRANT", "g1")
    }

    private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", emptyMap<String, Any>(), Long::class.java)!!
    private fun ledgerLines(account: String = "a1") = jdbc.queryForObject("select count(*) from point_entries where account_id = :a", mapOf("a" to account), Long::class.java)!!

    @Test
    fun `a spend is recorded with its actor, amount, one attempt and the ledger line it produced`() {
        val result = operations.spend("svc:order", "a1", 40, "ORDER", "o1")

        assertTrue(result.succeeded)
        val op = result.operation
        assertEquals("svc:order", op.actor)
        assertEquals(40, op.amount)
        assertEquals(1, op.attempts)
        assertEquals("OK", op.resultCode)
        assertEquals(result.entry!!.id, op.resultEntryId)
        assertEquals(PointEntryKind.SPEND, result.entry!!.kind)
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals("SUCCEEDED", log.attempts(op.operationId).single().outcome)
    }

    @Test
    fun `an insufficient spend is a confirmed failure with its code, the ledger is untouched and a repeat answers from the record`() {
        val result = operations.spend("svc:order", "a1", 500, "ORDER", "o1")

        assertEquals(PointOperationStatus.FAILED, result.status)
        assertEquals("POINT.INSUFFICIENT", result.operation.resultCode)
        assertNull(result.entry)
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(1, ledgerLines())
        val again = operations.spend("svc:order", "a1", 500, "ORDER", "o1")
        assertEquals(result.operation.operationId, again.operation.operationId)
        assertEquals(1, again.operation.attempts, "a closed failure answers from the record, no new attempt")
        assertEquals("FAILED", log.attempts(result.operation.operationId).single().outcome)
    }

    @Test
    fun `when the ledger transaction fails the receipt and the attempt stay, and nothing is recorded as a success`() {
        class ExplodingStore(private val delegate: PointStore) : PointStore by delegate {
            override fun spend(accountId: String, amount: Long, kind: PointEntryKind, sourceType: String, sourceRef: String, reason: String?, now: Instant): PointEntry =
                throw IllegalStateException("database went away mid-write")
        }
        val ops = JdbcPointOperations(ExplodingStore(PointDb.store), log, PointDb.txManager, PointDb.time)

        assertFailsWith<IllegalStateException> { ops.spend("svc:order", "a1", 40, "ORDER", "o1") }

        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(1, ledgerLines())
        val op = log.findBySource("ORDER", "o1")!!
        assertEquals(PointOperationStatus.UNKNOWN, op.status, "never a success for a write that did not commit")
        assertNull(op.resultEntryId)
        assertEquals(1, log.attempts(op.operationId).size)
        assertEquals("UNKNOWN", log.attempts(op.operationId).single().outcome)
    }

    @Test
    fun `every method refuses to run inside an active transaction and records nothing`() {
        PointDb.tx.executeWithoutResult {
            assertFailsWith<IllegalStateException> { operations.spend("svc:order", "a1", 40, "ORDER", "o1") }
            assertFailsWith<IllegalStateException> { operations.earn("svc:order", "a1", 40, null, "GRANT", "g-in-tx") }
            assertFailsWith<IllegalStateException> { operations.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "CANCEL", "c1") }
            assertFailsWith<IllegalStateException> { operations.resolve("op_x") }
            assertFailsWith<IllegalStateException> { operations.abandon("op_x") }
            assertFailsWith<IllegalStateException> { operations.find("op_x") }
            assertFailsWith<IllegalStateException> { operations.findBySource("ORDER", "o1") }
        }

        assertEquals(0, count("point_operations"))
        assertEquals(100, wallet.balance("a1").balance)
    }

    @Test
    fun `if the receipt cannot be written the ledger is never touched`() {
        class CountingStore(private val delegate: PointStore) : PointStore by delegate {
            var spends = 0
            override fun spend(accountId: String, amount: Long, kind: PointEntryKind, sourceType: String, sourceRef: String, reason: String?, now: Instant): PointEntry {
                spends++
                return delegate.spend(accountId, amount, kind, sourceType, sourceRef, reason, now)
            }
        }
        class BrokenLog(private val delegate: PointOperationLog) : PointOperationLog by delegate {
            override fun accept(receipt: PointReceipt, now: Instant): AcceptedOperation = throw IllegalStateException("receipt table unavailable")
        }
        val store = CountingStore(PointDb.store)
        val broken = JdbcPointOperations(store, BrokenLog(log), PointDb.txManager, PointDb.time)

        assertFailsWith<IllegalStateException> { broken.spend("svc:order", "a1", 40, "ORDER", "o1") }

        assertEquals(0, store.spends)
        assertEquals(100, wallet.balance("a1").balance)
        assertNull(wallet.entryOf("ORDER", "o1"))
    }

    @Test
    fun `the same key with another request is a source conflict and the record does not change`() {
        val first = operations.spend("svc:order", "a1", 40, "ORDER", "o1")

        assertFailsWith<PointSourceConflictException> { operations.spend("svc:order", "a1", 4, "ORDER", "o1") }
        assertFailsWith<PointSourceConflictException> { operations.earn("svc:order", "a1", 40, null, "ORDER", "o1") }

        val op = log.find(first.operation.operationId)!!
        assertEquals(40, op.amount)
        assertEquals(1, op.attempts)
        assertEquals(PointOperationStatus.SUCCEEDED, op.status)
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals(1, count("point_operations"), "only the one operation exists")
    }

    @Test
    fun `the same key from four workers at once is one operation and one deduction`() {
        val pool = Executors.newFixedThreadPool(4)
        val results = try {
            val ready = CountDownLatch(4)
            val go = CountDownLatch(1)
            val futures = (0 until 4).map { pool.submit<Result<dev.sumin.skeleton.point.PointOperationResult>> { ready.countDown(); go.await(); runCatching { operations.spend("svc:order", "a1", 30, "ORDER", "same") } } }
            ready.await()
            go.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // MySQL 의 낡은 스냅샷에서는 늦은 쪽이 던질 수 있다 - 어느 쪽이든 두 번 빠지지는 않고 작업은 하나다
        assertTrue(results.any { it.isSuccess && it.getOrThrow().succeeded })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is IllegalStateException }, "only a 'retry' signal may escape: " + results.mapNotNull { it.exceptionOrNull()?.toString() })
        assertEquals(70, wallet.balance("a1").balance)
        assertEquals(2, ledgerLines(), "the grant plus one deduction")
        val ops = jdbc.queryForObject("select count(*) from point_operations where source_ref = 'same'", emptyMap<String, Any>(), Long::class.java)!!
        assertEquals(1, ops)
        val op = log.findBySource("ORDER", "same")!!
        assertTrue(op.attempts in 1..4)
        assertEquals(op.attempts, log.attempts(op.operationId).size)
    }

    @Test
    fun `an earn is recorded and repeating it answers with the same line`() {
        val first = operations.earn("svc:reward", "a2", 50, null, "GRANT", "g-ops")
        val again = operations.earn("svc:reward", "a2", 50, null, "GRANT", "g-ops")

        assertTrue(first.succeeded)
        assertEquals(first.entry!!.id, again.entry!!.id)
        assertEquals(first.operation.operationId, again.operation.operationId)
        assertEquals(50, wallet.balance("a2").balance)
        assertEquals(1, ledgerLines("a2"))
        assertFailsWith<PointSourceConflictException> { operations.earn("svc:reward", "a2", 60, null, "GRANT", "g-ops") }
    }

    /** N 번째 커밋에서 응답이 유실된 모양 - [afterCommit] 이면 커밋은 DB 에 들어간 뒤 예외, 아니면 커밋되기 전에 예외(되돌아간다) */
    private class LoseCommitAck(source: javax.sql.DataSource, private val failOn: Int, private val afterCommit: Boolean) : DataSourceTransactionManager(source) {
        var commits = 0
        override fun doCommit(status: DefaultTransactionStatus) {
            val n = ++commits
            if (n == failOn && !afterCommit) throw IllegalStateException("connection reset before commit")
            super.doCommit(status)
            if (n == failOn && afterCommit) throw IllegalStateException("connection reset after commit")
        }
    }

    private fun flakyOperations(failOn: Int, afterCommit: Boolean): JdbcPointOperations {
        // 커밋 순서: 영수증(1) · 시도 열기(2) · 원장 + 성공 표시(3) · 불명 표시(4)
        val flaky = LoseCommitAck(PointDb.dataSource, failOn, afterCommit)
        return JdbcPointOperations(
            JdbcPointStore(jdbc, TransactionTemplate(flaky), DbTestDatabase.dialect), JdbcPointOperationLog(jdbc, flaky, DbTestDatabase.dialect), flaky, TimeProvider { PointDb.now },
        )
    }

    @Test
    fun `a lost acknowledgement after the commit is never recorded as a failure - the record already says succeeded because the mark rode in the same commit, and a retry deducts nothing more`() {
        val ops = flakyOperations(failOn = 3, afterCommit = true)

        assertFailsWith<IllegalStateException> { ops.spend("svc:order", "a1", 40, "ORDER", "o1") }   // 호출자는 답을 못 받았다

        val op = log.findBySource("ORDER", "o1")!!
        assertEquals(PointOperationStatus.SUCCEEDED, op.status)
        assertNotNull(wallet.entryOf("ORDER", "o1"), "the deduction really committed")
        assertEquals(60, wallet.balance("a1").balance)

        val retry = ops.spend("svc:order", "a1", 40, "ORDER", "o1")   // 같은 키 재시도

        assertTrue(retry.succeeded)
        assertEquals(op.operationId, retry.operation.operationId)
        assertEquals(retry.operation.resultEntryId, retry.entry!!.id)
        assertEquals(60, wallet.balance("a1").balance, "never deducted twice")
        assertEquals(2, ledgerLines(), "the grant plus one deduction")
    }

    @Test
    fun `a commit that never happened is unknown, not a confirmed failure - the same-key retry is a second attempt of the same operation and deducts once`() {
        val ops = flakyOperations(failOn = 3, afterCommit = false)

        assertFailsWith<IllegalStateException> { ops.spend("svc:order", "a1", 40, "ORDER", "o1") }

        val op = log.findBySource("ORDER", "o1")!!
        assertEquals(PointOperationStatus.UNKNOWN, op.status, "we cannot tell - not FAILED")
        assertEquals("POINT.UNKNOWN", op.resultCode)
        assertNull(wallet.entryOf("ORDER", "o1"))

        val retry = ops.spend("svc:order", "a1", 40, "ORDER", "o1")

        assertTrue(retry.succeeded)
        assertEquals(op.operationId, retry.operation.operationId)
        assertEquals(2, retry.operation.attempts, "the retry is an attempt of the same operation")
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals(2, ledgerLines())
        assertEquals(listOf("UNKNOWN", "SUCCEEDED"), log.attempts(op.operationId).map { it.outcome })
    }
}
