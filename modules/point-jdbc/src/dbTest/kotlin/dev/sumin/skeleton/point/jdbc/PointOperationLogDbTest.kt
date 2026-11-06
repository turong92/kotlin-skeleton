package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.point.PointCompensation
import dev.sumin.skeleton.point.PointOperationKind
import dev.sumin.skeleton.point.PointOperationStatus
import dev.sumin.skeleton.point.PointReceipt
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 작업 기록 저장소: 영수증 · 실패 시도는 바깥 트랜잭션이 롤백돼도 남고, 성공 표시는 바깥과 함께 사라진다 */
class PointOperationLogDbTest {
    private val log = PointDb.log
    private val jdbc = PointDb.jdbc
    private val now: Instant = Instant.parse("2026-11-07T00:00:00.123456Z")

    @BeforeTest
    fun clean() {
        jdbc.update("delete from point_spend_allocations", emptyMap<String, Any>())
        jdbc.update("delete from point_restore_links", emptyMap<String, Any>())
        jdbc.update("delete from point_operation_attempts", emptyMap<String, Any>())
        jdbc.update("delete from point_operations", emptyMap<String, Any>())
        jdbc.update("delete from point_entries", emptyMap<String, Any>())
        jdbc.update("delete from point_balances", emptyMap<String, Any>())
    }

    private fun receipt(ref: String = "o1", account: String = "a1", amount: Long? = 40, kind: PointOperationKind = PointOperationKind.SPEND) =
        PointReceipt(account, kind, amount, "ORDER", ref, "f".repeat(64), "svc:order")

    private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", emptyMap<String, Any>(), Long::class.java)

    @Test
    fun `a receipt is written once per key and a repeat returns the existing operation`() {
        val first = log.accept(receipt(), now)
        val again = log.accept(receipt(), now.plusSeconds(5))

        assertTrue(first.created)
        assertFalse(again.created)
        assertEquals(first.operation.operationId, again.operation.operationId)
        assertEquals(PointOperationStatus.ACCEPTED, first.operation.status)
        assertEquals(PointCompensation.NONE, first.operation.compensation)
        assertEquals(now, first.operation.acceptedAt)
        assertEquals(1, count("point_operations"))
        assertEquals("svc:order", log.findBySource("ORDER", "o1")!!.actor)
    }

    @Test
    fun `without a surrounding transaction each call commits on its own - a receipt, an attempt and a failure are visible at once`() {
        val op = log.accept(receipt(), now).operation
        val n = log.beginAttempt(op.operationId, now)
        log.fail(op.operationId, n, "POINT.INSUFFICIENT", now)

        val after = log.findBySource("ORDER", "o1")!!
        assertEquals(PointOperationStatus.FAILED, after.status)
        assertEquals("POINT.INSUFFICIENT", after.resultCode)
        assertEquals(1, after.attempts)
        val attempt = log.attempts(op.operationId).single()
        assertEquals("FAILED", attempt.outcome)
        assertNotNull(attempt.finishedAt)
    }

    @Test
    fun `a success mark rolls back together with the caller transaction - a rolled-back write is never recorded as a success`() {
        val op = log.accept(receipt(), now).operation
        val n = log.beginAttempt(op.operationId, now)

        PointDb.tx.executeWithoutResult { status ->
            log.succeed(op.operationId, n, "pt_x", now)
            status.setRollbackOnly()
        }

        val after = log.find(op.operationId)!!
        assertEquals(PointOperationStatus.PROCESSING, after.status)
        assertNull(after.resultEntryId)
        assertNull(log.attempts(op.operationId).single().outcome)
    }

    @Test
    fun `a success mark commits with the caller transaction`() {
        val op = log.accept(receipt(), now).operation
        val n = log.beginAttempt(op.operationId, now)

        PointDb.tx.executeWithoutResult { log.succeed(op.operationId, n, "pt_x", now.plusSeconds(1)) }

        val after = log.find(op.operationId)!!
        assertEquals(PointOperationStatus.SUCCEEDED, after.status)
        assertEquals("pt_x", after.resultEntryId)
        assertEquals("OK", after.resultCode)
        assertEquals(now.plusSeconds(1), after.finishedAt)
        assertEquals("SUCCEEDED", log.attempts(op.operationId).single().outcome)
    }

    @Test
    fun `each attempt is its own line and a finished operation is not reopened by a late failure or unknown`() {
        val op = log.accept(receipt(), now).operation
        assertEquals(1, log.beginAttempt(op.operationId, now))
        log.markUnknown(op.operationId, 1, "POINT.UNKNOWN", now)
        assertEquals(PointOperationStatus.UNKNOWN, log.find(op.operationId)!!.status)
        assertEquals(2, log.beginAttempt(op.operationId, now.plusSeconds(1)))
        assertEquals(PointOperationStatus.PROCESSING, log.find(op.operationId)!!.status)
        PointDb.tx.executeWithoutResult { log.succeed(op.operationId, 2, "pt_x", now.plusSeconds(2)) }

        log.fail(op.operationId, 2, "POINT.INSUFFICIENT", now.plusSeconds(3))
        log.markUnknown(op.operationId, 2, "POINT.UNKNOWN", now.plusSeconds(3))
        log.abandon(op.operationId, now.plusSeconds(3))

        assertEquals(PointOperationStatus.SUCCEEDED, log.find(op.operationId)!!.status)
        assertEquals(listOf(1, 2), log.attempts(op.operationId).map { it.n })
        assertEquals(listOf("UNKNOWN", "SUCCEEDED"), log.attempts(op.operationId).map { it.outcome })
    }

    @Test
    fun `an abandoned operation is corrected to a success once the ledger line shows up, and only then`() {
        val op = log.accept(receipt(), now).operation
        log.beginAttempt(op.operationId, now)
        log.markUnknown(op.operationId, 1, "POINT.UNKNOWN", now)
        log.abandon(op.operationId, now.plusSeconds(60))
        assertEquals(PointOperationStatus.ABANDONED, log.find(op.operationId)!!.status)

        assertTrue(log.recoverSucceeded(op.operationId, "pt_late", now.plusSeconds(120)))

        val after = log.find(op.operationId)!!
        assertEquals(PointOperationStatus.SUCCEEDED, after.status)
        assertEquals("pt_late", after.resultEntryId)
        assertFalse(log.recoverSucceeded(op.operationId, "pt_other", now.plusSeconds(180)), "already a success")
        assertEquals("pt_late", log.find(op.operationId)!!.resultEntryId)
    }

    @Test
    fun `a completed compensation is sticky - a late request cannot turn it back`() {
        val op = log.accept(receipt(), now).operation
        log.setCompensation(op.operationId, PointCompensation.REQUESTED)
        log.setCompensation(op.operationId, PointCompensation.DONE)
        log.setCompensation(op.operationId, PointCompensation.FAILED)
        log.setCompensation(op.operationId, PointCompensation.REQUESTED)

        assertEquals(PointCompensation.DONE, log.find(op.operationId)!!.compensation)
    }

    @Test
    fun `compensation state is kept on the original operation`() {
        val op = log.accept(receipt(), now).operation
        log.setCompensation(op.operationId, PointCompensation.REQUESTED)
        assertEquals(PointCompensation.REQUESTED, log.find(op.operationId)!!.compensation)
        val cancel = log.accept(receipt("c1", kind = PointOperationKind.CANCEL_SPEND, amount = null).copy(originOperationId = op.operationId), now).operation
        assertEquals(op.operationId, cancel.originOperationId)
        assertNull(cancel.amount)
    }

    @Test
    fun `the record has no column that could hold a secret, a body or a reason`() {
        val columns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_name in ('point_operations', 'point_operation_attempts') order by 1",
            emptyMap<String, Any>(), String::class.java,
        ).map { it!!.lowercase() }.toSet()

        assertEquals(
            setOf(
                "operation_id", "account_id", "kind", "amount", "source_type", "source_ref", "fingerprint", "actor", "status", "origin_operation_id", "compensation",
                "result_entry_id", "result_code", "attempts", "accepted_at", "last_attempt_at", "finished_at", "n", "started_at", "finished_at", "outcome", "error_code",
            ),
            columns,
        )
    }

    @Test
    fun `erasing an account removes its operations and attempts, and only theirs`() {
        val mine = log.accept(receipt("o1", "a1"), now).operation
        log.beginAttempt(mine.operationId, now)
        val theirs = log.accept(receipt("o2", "a2"), now).operation
        log.beginAttempt(theirs.operationId, now)

        PointDb.store.eraseAccount("a1")

        assertNull(log.find(mine.operationId))
        assertEquals(emptyList(), log.attempts(mine.operationId))
        assertNotNull(log.find(theirs.operationId))
        assertEquals(1, log.attempts(theirs.operationId).size)
    }
}
