package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.point.AcceptedOperation
import dev.sumin.skeleton.point.PointCompensation
import dev.sumin.skeleton.point.PointOperation
import dev.sumin.skeleton.point.PointOperationAttempt
import dev.sumin.skeleton.point.PointOperationKind
import dev.sumin.skeleton.point.PointOperationLog
import dev.sumin.skeleton.point.PointOperationScheduler
import dev.sumin.skeleton.point.PointOperationStatus
import dev.sumin.skeleton.point.PointReceipt
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * `point_operations` · `point_operation_attempts`. 트랜잭션 경계가 계약이다([PointOperationLog]): 트랜잭션이 없으면 호출마다 짧은 트랜잭션 하나, 있으면 합류한다.
 * [PointOperations] 가 영수증 · 시도 열기 · 실패 · 불명 · 포기는 트랜잭션 밖에서(각자 짧은 트랜잭션), 성공 표시 · 보상 완료는 원장 쓰기와 같은 자기 트랜잭션 안에서 부른다. 상태 전이는 모두 조건 걸린 문장이라 끝난 작업(SUCCEEDED · FAILED)을 늦게 온 쓰기가 되살리지 못한다.
 * 같은 작업 줄의 갱신끼리는 행 잠금이 줄을 세운다.
 */
class JdbcPointOperationLog(
    private val jdbc: NamedParameterJdbcTemplate,
    transactions: PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val ids: SortableIds = SortableIds.system,
    /** 불명 건 조회 예약 - 영수증과 **같은 트랜잭션**에서 부른다. 큐가 없으면 null */
    private val scheduler: () -> PointOperationScheduler? = { null },
    private val firstDelay: Duration = Duration.ofSeconds(60),
) : PointOperationLog {
    // 트랜잭션이 없으면 호출마다 짧은 트랜잭션 하나를 열고, 있으면 합류한다(REQUIRES_NEW 는 쓰지 않는다 - 같은 줄을 잠근 채 새 트랜잭션이 같은 줄을 건드리면 자기 자신과 교착한다).
    // [PointOperations] 는 활성 트랜잭션 안에서 불리면 거절하고, 원장 쓰기 + 성공 표시 + 보상 완료 표시만 자기가 연 트랜잭션 하나로 묶는다.
    private val tx = TransactionTemplate(transactions)
    private val insertIgnore = dialect.insertIgnore("point_operations", COLUMNS, listOf("source_type", "source_ref"))

    override fun accept(receipt: PointReceipt, now: Instant): AcceptedOperation = tx.execute {
        val id = ids.newId("op_")
        jdbc.update(
            insertIgnore,
            mapOf(
                "operation_id" to id, "account_id" to receipt.accountId, "kind" to receipt.kind.name, "amount" to receipt.amount,
                "source_type" to receipt.sourceType, "source_ref" to receipt.sourceRef, "fingerprint" to receipt.fingerprint, "actor" to receipt.actor,
                "status" to PointOperationStatus.ACCEPTED.name, "origin_operation_id" to receipt.originOperationId, "compensation" to PointCompensation.NONE.name,
                "result_entry_id" to null, "result_code" to null, "attempts" to 0, "accepted_at" to dialect.instantParam(now),
                "last_attempt_at" to null, "finished_at" to null,
            ),
        )
        // 갱신 행 수로는 못 가른다(MySQL) - 방금 넣은 id 가 이 키의 줄이면 우리가 만든 것이다
        val found = selectBySource(receipt.sourceType, receipt.sourceRef) ?: error("point operation vanished right after its receipt")
        val created = found.operationId == id
        // 영수증이 저장됐으면 조회 작업도 반드시 있다 - 같은 트랜잭션이라 예약이 던지면 영수증도 사라지고 원장에 닿기 전에 멈춘다
        if (created) scheduler()?.schedule(id, now.plus(firstDelay))
        AcceptedOperation(found, created)
    }!!

    override fun beginAttempt(operationId: String, now: Instant, reschedule: Boolean): Int = tx.execute {
        val changed = jdbc.update(
            "update point_operations set attempts = attempts + 1, status = 'PROCESSING', last_attempt_at = :now " +
                "where operation_id = :id and status not in ('SUCCEEDED', 'FAILED')",
            mapOf("id" to operationId, "now" to dialect.instantParam(now)),
        )
        check(changed == 1) { "point operation cannot start another attempt (finished or unknown)" }
        val n = jdbc.queryForObject("select attempts from point_operations where operation_id = :id", mapOf("id" to operationId), Int::class.java)!!
        jdbc.update(
            "insert into point_operation_attempts (operation_id, n, started_at) values (:id, :n, :now)",
            mapOf("id" to operationId, "n" to n, "now" to dialect.instantParam(now)),
        )
        // 같은 키 재시도로 열린 시도면 조회 작업을 다시 건다 - 먼저 걸린 작업은 이미 끝났거나 포기했을 수 있다(시도와 같은 트랜잭션)
        if (reschedule) scheduler()?.schedule(operationId, now.plus(firstDelay))
        n
    }!!

    override fun succeed(operationId: String, attempt: Int, resultEntryId: String, now: Instant) {
        tx.executeWithoutResult {
            val changed = jdbc.update(
                "update point_operations set status = 'SUCCEEDED', result_entry_id = :entry, result_code = 'OK', finished_at = :now " +
                    "where operation_id = :id and status <> 'FAILED'",
                mapOf("id" to operationId, "entry" to resultEntryId, "now" to dialect.instantParam(now)),
            )
            check(changed == 1) { "point operation was already closed as failed" }
            closeAttempt(operationId, attempt, "SUCCEEDED", null, now, replaceUnknown = true)
        }
    }

    override fun fail(operationId: String, attempt: Int, errorCode: String, now: Instant) {
        tx.executeWithoutResult {
            jdbc.update(
                "update point_operations set status = 'FAILED', result_code = :code, finished_at = :now where operation_id = :id and status not in ('SUCCEEDED', 'FAILED')",
                mapOf("id" to operationId, "code" to errorCode, "now" to dialect.instantParam(now)),
            )
            closeAttempt(operationId, attempt, "FAILED", errorCode, now)
        }
    }

    override fun markUnknown(operationId: String, attempt: Int?, errorCode: String, now: Instant) {
        tx.executeWithoutResult {
            jdbc.update(
                "update point_operations set status = 'UNKNOWN', result_code = :code where operation_id = :id and status not in ('SUCCEEDED', 'FAILED')",
                mapOf("id" to operationId, "code" to errorCode),
            )
            if (attempt != null) closeAttempt(operationId, attempt, "UNKNOWN", errorCode, now)
        }
    }

    override fun abandon(operationId: String, now: Instant) {
        tx.executeWithoutResult {
            jdbc.update(
                "update point_operations set status = 'ABANDONED', finished_at = :now where operation_id = :id and status not in ('SUCCEEDED', 'FAILED')",
                mapOf("id" to operationId, "now" to dialect.instantParam(now)),
            )
        }
    }

    override fun recoverSucceeded(operationId: String, resultEntryId: String, now: Instant): Boolean = tx.execute {
        val changed = jdbc.update(
            "update point_operations set status = 'SUCCEEDED', result_entry_id = :entry, result_code = 'OK', finished_at = :now " +
                "where operation_id = :id and status in ('ACCEPTED', 'PROCESSING', 'UNKNOWN', 'ABANDONED')",
            mapOf("id" to operationId, "entry" to resultEntryId, "now" to dialect.instantParam(now)),
        )
        if (changed == 1) {
            val last = jdbc.queryForObject("select attempts from point_operations where operation_id = :id", mapOf("id" to operationId), Int::class.java)!!
            if (last > 0) closeAttempt(operationId, last, "SUCCEEDED", null, now, replaceUnknown = true)
        }
        changed == 1
    }!!

    override fun setCompensation(operationId: String, compensation: PointCompensation) {
        tx.executeWithoutResult {
            // 한 번 DONE 이 되면 바뀌지 않는다 - 늦은 요청 · 재시도가 완료를 되돌리지 못하게
            jdbc.update(
                "update point_operations set compensation = :c where operation_id = :id and compensation <> 'DONE'",
                mapOf("id" to operationId, "c" to compensation.name),
            )
        }
    }

    // 읽기는 트랜잭션 없이 한 문장씩이다(활성 트랜잭션이 없으면 자동 커밋) - 낡은 스냅샷을 타지 않는다.
    override fun find(operationId: String): PointOperation? =
        jdbc.query("$SELECT where operation_id = :id", mapOf("id" to operationId), rows).firstOrNull()

    override fun findBySource(sourceType: String, sourceRef: String): PointOperation? = selectBySource(sourceType, sourceRef)

    private fun selectBySource(sourceType: String, sourceRef: String): PointOperation? =
        jdbc.query("$SELECT where source_type = :t and source_ref = :r", mapOf("t" to sourceType, "r" to sourceRef), rows).firstOrNull()

    override fun attempts(operationId: String): List<PointOperationAttempt> = selectAttempts(operationId)

    private fun selectAttempts(operationId: String): List<PointOperationAttempt> =
        jdbc.query(
            "select operation_id, n, started_at, finished_at, outcome, error_code from point_operation_attempts where operation_id = :id order by n",
            mapOf("id" to operationId),
        ) { rs, _ ->
            PointOperationAttempt(
                rs.getString("operation_id"), rs.getInt("n"), dialect.readInstant(rs, "started_at")!!, dialect.readInstant(rs, "finished_at"),
                rs.getString("outcome"), rs.getString("error_code"),
            )
        }

    /** 열린 시도(결과가 아직 없다)를 닫는다. [replaceUnknown] 이면 「불명」으로 닫힌 시도도 덮는다(나중에 성공으로 확인됐다) */
    private fun closeAttempt(operationId: String, attempt: Int, outcome: String, errorCode: String?, now: Instant, replaceUnknown: Boolean = false) {
        val open = if (replaceUnknown) "(outcome is null or outcome = 'UNKNOWN')" else "outcome is null"
        jdbc.update(
            "update point_operation_attempts set outcome = :outcome, error_code = :code, finished_at = :now where operation_id = :id and n = :n and $open",
            mapOf("id" to operationId, "n" to attempt, "outcome" to outcome, "code" to errorCode, "now" to dialect.instantParam(now)),
        )
    }

    private val rows = RowMapper<PointOperation> { rs, _ -> operation(rs) }

    private fun operation(rs: ResultSet) = PointOperation(
        operationId = rs.getString("operation_id"),
        accountId = rs.getString("account_id"),
        kind = PointOperationKind.valueOf(rs.getString("kind")),
        amount = rs.getLong("amount").takeUnless { rs.wasNull() },
        sourceType = rs.getString("source_type"),
        sourceRef = rs.getString("source_ref"),
        fingerprint = rs.getString("fingerprint"),
        actor = rs.getString("actor"),
        status = PointOperationStatus.valueOf(rs.getString("status")),
        originOperationId = rs.getString("origin_operation_id"),
        compensation = PointCompensation.valueOf(rs.getString("compensation")),
        resultEntryId = rs.getString("result_entry_id"),
        resultCode = rs.getString("result_code"),
        attempts = rs.getInt("attempts"),
        acceptedAt = dialect.readInstant(rs, "accepted_at")!!,
        lastAttemptAt = dialect.readInstant(rs, "last_attempt_at"),
        finishedAt = dialect.readInstant(rs, "finished_at"),
    )

    private companion object {
        val COLUMNS = listOf(
            "operation_id", "account_id", "kind", "amount", "source_type", "source_ref", "fingerprint", "actor", "status", "origin_operation_id", "compensation",
            "result_entry_id", "result_code", "attempts", "accepted_at", "last_attempt_at", "finished_at",
        )
        val SELECT = "select ${COLUMNS.joinToString()} from point_operations"
    }
}
