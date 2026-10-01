package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.transaction.support.TransactionTemplate

/**
 * skeleton_jobs 접근. 시각은 SqlDialect 로 바인딩한다 (방언 모듈이 결정, JVM 시간대 무관).
 *
 * claim 은 한 트랜잭션에서 `SELECT … FOR UPDATE SKIP LOCKED` → `UPDATE … RUNNING` 으로 잡아서,
 * 워커가 여러 개여도 같은 잡을 두 번 집지 않는다. Redis 락 불필요.
 */
class JdbcJobRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val dialect: SqlDialect,
) {
    fun insert(type: String, payloadJson: String, runAt: Instant, maxAttempts: Int, now: Instant): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """insert into skeleton_jobs
               (job_type, payload_json, status, attempts, max_attempts, next_run_at, created_at, updated_at)
               values (:type, :payload, 'PENDING', 0, :maxAttempts, :runAt, :now, :now)""",
        )
            .param("type", type).param("payload", payloadJson).param("maxAttempts", maxAttempts)
            .param("runAt", dialect.instantParam(runAt)).param("now", dialect.instantParam(now))
            .update(keys, "id") // 키 칼럼 지정: PG 는 지정 안 하면 모든 칼럼을 키로 돌려준다
        return keys.key!!.toLong()
    }

    /** 실행할 잡을 잡는다. 잡힌 잡은 RUNNING 이고 attempts 가 1 올라간 상태로 돌아온다. */
    fun claim(workerId: String, limit: Int, now: Instant): List<Job> =
        transactions.execute {
            val ids = jdbc.sql(
                """select id from skeleton_jobs
                   where status = 'PENDING' and next_run_at <= :now
                   order by next_run_at, id
                   limit :limit
                   for update skip locked""",
            ).param("now", dialect.instantParam(now)).param("limit", limit).query(Long::class.java).list()
            if (ids.isEmpty()) return@execute emptyList()
            jdbc.sql(
                """update skeleton_jobs
                   set status = 'RUNNING', locked_by = :worker, locked_at = :now, attempts = attempts + 1, updated_at = :now
                   where id in (:ids)""",
            ).param("worker", workerId).param("now", dialect.instantParam(now)).param("ids", ids).update()
            jdbc.sql("select * from skeleton_jobs where id in (:ids) order by next_run_at, id")
                .param("ids", ids).query(::map).list()
        } ?: emptyList()

    fun markDone(id: Long, now: Instant) {
        jdbc.sql("update skeleton_jobs set status = 'DONE', locked_by = null, locked_at = null, updated_at = :now where id = :id")
            .param("id", id).param("now", dialect.instantParam(now)).update()
    }

    fun markRetry(id: Long, nextRunAt: Instant, error: String, now: Instant) {
        jdbc.sql(
            """update skeleton_jobs
               set status = 'PENDING', next_run_at = :next, last_error = :error, locked_by = null, locked_at = null, updated_at = :now
               where id = :id""",
        ).param("id", id).param("next", dialect.instantParam(nextRunAt)).param("error", error.take(4000)).param("now", dialect.instantParam(now)).update()
    }

    fun markDead(id: Long, error: String, now: Instant) {
        jdbc.sql(
            """update skeleton_jobs
               set status = 'DEAD', last_error = :error, locked_by = null, locked_at = null, updated_at = :now
               where id = :id""",
        ).param("id", id).param("error", error.take(4000)).param("now", dialect.instantParam(now)).update()
    }

    /** 워커가 죽어 RUNNING 으로 남은 잡을 되돌린다. attempts 는 이미 올라가 있으므로 그대로 둔다 */
    fun recoverStale(lockedBefore: Instant, now: Instant): Int =
        jdbc.sql(
            """update skeleton_jobs
               set status = 'PENDING', locked_by = null, locked_at = null, updated_at = :now
               where status = 'RUNNING' and locked_at < :before""",
        ).param("before", dialect.instantParam(lockedBefore)).param("now", dialect.instantParam(now)).update()

    fun findById(id: Long): Job? =
        jdbc.sql("select * from skeleton_jobs where id = :id").param("id", id).query(::map).optional().orElse(null)

    fun countByStatus(status: JobStatus): Long =
        jdbc.sql("select count(*) from skeleton_jobs where status = :status").param("status", status.name)
            .query(Long::class.java).single()

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): Job = Job(
        id = rs.getLong("id"),
        type = rs.getString("job_type"),
        payloadJson = rs.getString("payload_json"),
        status = JobStatus.valueOf(rs.getString("status")),
        attempts = rs.getInt("attempts"),
        maxAttempts = rs.getInt("max_attempts"),
        nextRunAt = dialect.readInstant(rs, "next_run_at")!!,
        lockedBy = rs.getString("locked_by"),
        lockedAt = dialect.readInstant(rs, "locked_at"),
        lastError = rs.getString("last_error"),
        createdAt = dialect.readInstant(rs, "created_at")!!,
        updatedAt = dialect.readInstant(rs, "updated_at")!!,
    )
}
