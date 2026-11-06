package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.PointCompensation
import dev.sumin.skeleton.point.PointOperationKind
import dev.sumin.skeleton.point.PointOperationStatus
import dev.sumin.skeleton.point.PointReceipt
import dev.sumin.skeleton.point.PointSourceConflictException
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

/** 보상(사용 취소): 원 작업과 연결하고, 원 차감이 원장에 있다고 확인된 건에만 한다 */
class PointCompensationDbTest {
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

    private fun ledgerLines() = jdbc.queryForObject("select count(*) from point_entries where account_id = 'a1'", emptyMap<String, Any>(), Long::class.java)!!

    @Test
    fun `a cancellation is linked to the original operation and marks its compensation requested then done`() {
        val spent = operations.spend("svc:order", "a1", 40, "ORDER", "o1").operation

        val cancel = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "o1:cancel")

        assertTrue(cancel.succeeded)
        assertEquals(PointOperationKind.CANCEL_SPEND, cancel.operation.kind)
        assertEquals(spent.operationId, cancel.operation.originOperationId)
        assertNull(cancel.operation.amount)
        assertEquals(PointEntryKindRestore, cancel.entry!!.kind)
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(PointCompensation.DONE, log.find(spent.operationId)!!.compensation)
        assertEquals(PointOperationStatus.SUCCEEDED, log.find(spent.operationId)!!.status)
    }

    @Test
    fun `a repeat of the same cancel key answers from the record, another request on it is a source conflict`() {
        operations.spend("svc:order", "a1", 40, "ORDER", "o1")
        val first = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", 30, "ORDER_CANCEL", "c1")

        val again = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", 30, "ORDER_CANCEL", "c1")

        assertEquals(first.operation.operationId, again.operation.operationId)
        assertEquals(first.entry!!.id, again.entry!!.id)
        assertEquals(1, again.operation.attempts)
        assertEquals(90, wallet.balance("a1").balance)
        assertFailsWith<PointSourceConflictException> { operations.cancelSpend("svc:order", "a1", "ORDER", "o1", 10, "ORDER_CANCEL", "c1") }
        assertFailsWith<PointSourceConflictException> { operations.cancelSpend("svc:order", "a1", "ORDER", "other", 30, "ORDER_CANCEL", "c1") }
    }

    @Test
    fun `an over-cancellation is a confirmed failure with its code and marks the compensation failed`() {
        val spent = operations.spend("svc:order", "a1", 40, "ORDER", "o1").operation

        val cancel = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", 41, "ORDER_CANCEL", "c1")

        assertEquals(PointOperationStatus.FAILED, cancel.status)
        assertEquals("POINT.CANCEL_EXCEEDED", cancel.operation.resultCode)
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals(PointCompensation.FAILED, log.find(spent.operationId)!!.compensation)
    }

    @Test
    fun `a deduction made without the operations layer can still be cancelled, with no origin link`() {
        wallet.spend("a1", 40, "ORDER", "o1")

        val cancel = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "c1")

        assertTrue(cancel.succeeded)
        assertNull(cancel.operation.originOperationId)
        assertEquals(100, wallet.balance("a1").balance)
    }

    @Test
    fun `an unknown deduction with no ledger line is not compensated - the cancellation waits as unknown and does not touch the ledger`() {
        // 영수증과 시도만 있고 원장 줄은 없는 원 작업 (커밋 전에 응답이 유실된 모양)
        val origin = log.accept(PointReceipt("a1", PointOperationKind.SPEND, 40, "ORDER", "o1", "f".repeat(64), "svc:order"), PointDb.now).operation
        val n = log.beginAttempt(origin.operationId, PointDb.now)
        log.markUnknown(origin.operationId, n, "POINT.UNKNOWN", PointDb.now)

        val cancel = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "c1")

        assertEquals(PointOperationStatus.UNKNOWN, cancel.status)
        assertEquals("POINT.ORIGIN_UNRESOLVED", cancel.operation.resultCode)
        assertNull(cancel.entry)
        assertNull(wallet.entryOf("ORDER_CANCEL", "c1"))
        assertEquals(1, ledgerLines(), "no ledger line was written")
        assertEquals(PointOperationStatus.UNKNOWN, log.find(origin.operationId)!!.status)
        assertEquals(PointCompensation.REQUESTED, log.find(origin.operationId)!!.compensation, "still pending, not done")
        assertEquals(originLinkOf(cancel.operation.operationId), origin.operationId)
    }

    @Test
    fun `an original the ledger turns out to hold is confirmed first and then compensated`() {
        // 원 작업은 처리 중으로 남았는데 원장 줄은 늦게 커밋됐다
        val origin = log.accept(PointReceipt("a1", PointOperationKind.SPEND, 40, "ORDER", "o1", "f".repeat(64), "svc:order"), PointDb.now).operation
        log.beginAttempt(origin.operationId, PointDb.now)
        wallet.spend("a1", 40, "ORDER", "o1")

        val cancel = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "c1")

        assertTrue(cancel.succeeded)
        assertEquals(PointOperationStatus.SUCCEEDED, log.find(origin.operationId)!!.status, "resolved from the ledger before compensating")
        assertEquals(PointCompensation.DONE, log.find(origin.operationId)!!.compensation)
        assertEquals(100, wallet.balance("a1").balance)
    }

    @Test
    fun `an original that failed for sure has nothing to compensate`() {
        val failed = operations.spend("svc:order", "a1", 500, "ORDER", "o1").operation

        val cancel = operations.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "c1")

        assertEquals(PointOperationStatus.FAILED, cancel.status)
        assertEquals("POINT.SPEND_NOT_FOUND", cancel.operation.resultCode)
        assertEquals(PointCompensation.NONE, log.find(failed.operationId)!!.compensation)
        assertEquals(100, wallet.balance("a1").balance)
    }

    @Test
    fun `an acknowledgement lost on the compensation itself is retried with the same cancel key and restores once`() {
        operations.spend("svc:order", "a1", 40, "ORDER", "o1")
        // 취소 쪽 커밋 순서: 영수증(1) · 시도(2) · 원장 + 성공 표시(3)
        val flaky = object : DataSourceTransactionManager(PointDb.dataSource) {
            var commits = 0
            override fun doCommit(status: DefaultTransactionStatus) {
                val n = ++commits
                super.doCommit(status)
                if (n == 3) throw IllegalStateException("connection reset after commit")
            }
        }
        val ops = JdbcPointOperations(
            JdbcPointStore(jdbc, TransactionTemplate(flaky), DbTestDatabase.dialect), JdbcPointOperationLog(jdbc, flaky, DbTestDatabase.dialect), flaky, TimeProvider { PointDb.now },
        )

        assertFailsWith<IllegalStateException> { ops.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "c1") }
        val retry = ops.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "c1")

        assertTrue(retry.succeeded)
        assertEquals(100, wallet.balance("a1").balance, "restored once")
        assertEquals(1, jdbc.queryForObject("select count(*) from point_entries where kind = 'RESTORE'", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `the same cancel key from four workers at once is one operation and one restoration`() {
        operations.spend("svc:order", "a1", 40, "ORDER", "o1")
        val pool = Executors.newFixedThreadPool(4)
        val results = try {
            val ready = CountDownLatch(4)
            val go = CountDownLatch(1)
            val futures = (0 until 4).map { pool.submit<Result<dev.sumin.skeleton.point.PointOperationResult>> { ready.countDown(); go.await(); runCatching { operations.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "same") } } }
            ready.await()
            go.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(results.any { it.isSuccess && it.getOrThrow().succeeded })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is IllegalStateException }, "only a 'retry' signal may escape: " + results.mapNotNull { it.exceptionOrNull()?.toString() })
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(1, jdbc.queryForObject("select count(*) from point_operations where source_ref = 'same'", emptyMap<String, Any>(), Long::class.java))
        assertNotNull(wallet.entryOf("ORDER_CANCEL", "same"))
    }

    @Test
    fun `the compensation done mark rides in the ledger transaction - when the restore does not commit it stays requested`() {
        val spent = operations.spend("svc:order", "a1", 40, "ORDER", "o1").operation
        class ExplodingStore(private val delegate: dev.sumin.skeleton.point.PointStore) : dev.sumin.skeleton.point.PointStore by delegate {
            override fun cancelSpend(accountId: String, spendSourceType: String, spendSourceRef: String, amount: Long?, sourceType: String, sourceRef: String, now: java.time.Instant): dev.sumin.skeleton.point.PointEntry =
                throw IllegalStateException("database went away mid-write")
        }
        val ops = JdbcPointOperations(ExplodingStore(PointDb.store), log, PointDb.txManager, PointDb.time)

        assertFailsWith<IllegalStateException> { ops.cancelSpend("svc:order", "a1", "ORDER", "o1", null, "ORDER_CANCEL", "c1") }

        assertEquals(PointCompensation.REQUESTED, log.find(spent.operationId)!!.compensation, "never DONE for a restore that did not commit")
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals(PointOperationStatus.UNKNOWN, log.findBySource("ORDER_CANCEL", "c1")!!.status)
    }

    private fun originLinkOf(operationId: String) = log.find(operationId)!!.originOperationId

    private companion object {
        val PointEntryKindRestore = dev.sumin.skeleton.point.PointEntryKind.RESTORE
    }
}
