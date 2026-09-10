package dev.sumin.skeleton.jobqueue.jdbc

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.transaction.support.TransactionTemplate

/**
 * skeleton_jobs 접근. 시각은 UTC 벽시계 리터럴로 읽고 쓴다 (persistence-jdbc 와 같은 규약, JVM 시간대 무관).
 *
 * claim 은 한 트랜잭션에서 `SELECT … FOR UPDATE SKIP LOCKED` → `UPDATE … RUNNING` 으로 잡아서,
 * 워커가 여러 개여도 같은 잡을 두 번 집지 않는다. Redis 락 불필요.
 */
class JdbcJobRepository(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
) {
    fun insert(type: String, payloadJson: String, runAt: Instant, maxAttempts: Int, now: Instant): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """insert into skeleton_jobs
               (job_type, payload_json, status, attempts, max_attempts, next_run_at, created_at, updated_at)
               values (:type, :payload, 'PENDING', 0, :maxAttempts, :runAt, :now, :now)""",
        )
            .param("type", type).param("payload", payloadJson).param("maxAttempts", maxAttempts)
            .param("runAt", utc(runAt)).param("now", utc(now))
            .update(keys)
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
            ).param("now", utc(now)).param("limit", limit).query(Long::class.java).list()
            if (ids.isEmpty()) return@execute emptyList()
            jdbc.sql(
                """update skeleton_jobs
                   set status = 'RUNNING', locked_by = :worker, locked_at = :now, attempts = attempts + 1, updated_at = :now
                   where id in (:ids)""",
            ).param("worker", workerId).param("now", utc(now)).param("ids", ids).update()
            jdbc.sql("select * from skeleton_jobs where id in (:ids) order by next_run_at, id")
                .param("ids", ids).query(::map).list()
        } ?: emptyList()

    fun markDone(id: Long, now: Instant) {
        jdbc.sql("update skeleton_jobs set status = 'DONE', locked_by = null, locked_at = null, updated_at = :now where id = :id")
            .param("id", id).param("now", utc(now)).update()
    }

    fun markRetry(id: Long, nextRunAt: Instant, error: String, now: Instant) {
        jdbc.sql(
            """update skeleton_jobs
               set status = 'PENDING', next_run_at = :next, last_error = :error, locked_by = null, locked_at = null, updated_at = :now
               where id = :id""",
        ).param("id", id).param("next", utc(nextRunAt)).param("error", error.take(4000)).param("now", utc(now)).update()
    }

    fun markDead(id: Long, error: String, now: Instant) {
        jdbc.sql(
            """update skeleton_jobs
               set status = 'DEAD', last_error = :error, locked_by = null, locked_at = null, updated_at = :now
               where id = :id""",
        ).param("id", id).param("error", error.take(4000)).param("now", utc(now)).update()
    }

    /** 워커가 죽어 RUNNING 으로 남은 잡을 되돌린다. attempts 는 이미 올라가 있으므로 그대로 둔다 */
    fun recoverStale(lockedBefore: Instant, now: Instant): Int =
        jdbc.sql(
            """update skeleton_jobs
               set status = 'PENDING', locked_by = null, locked_at = null, updated_at = :now
               where status = 'RUNNING' and locked_at < :before""",
        ).param("before", utc(lockedBefore)).param("now", utc(now)).update()

    fun findById(id: Long): Job? =
        jdbc.sql("select * from skeleton_jobs where id = :id").param("id", id).query(::map).optional().orElse(null)

    fun countByStatus(status: JobStatus): Long =
        jdbc.sql("select count(*) from skeleton_jobs where status = :status").param("status", status.name)
            .query(Long::class.java).single()

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun instant(rs: ResultSet, column: String): Instant? =
        rs.getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): Job = Job(
        id = rs.getLong("id"),
        type = rs.getString("job_type"),
        payloadJson = rs.getString("payload_json"),
        status = JobStatus.valueOf(rs.getString("status")),
        attempts = rs.getInt("attempts"),
        maxAttempts = rs.getInt("max_attempts"),
        nextRunAt = instant(rs, "next_run_at")!!,
        lockedBy = rs.getString("locked_by"),
        lockedAt = instant(rs, "locked_at"),
        lastError = rs.getString("last_error"),
        createdAt = instant(rs, "created_at")!!,
        updatedAt = instant(rs, "updated_at")!!,
    )

    @Suppress("unused")
    private fun timestamp(instant: Instant): Timestamp = Timestamp.valueOf(utc(instant))
}
