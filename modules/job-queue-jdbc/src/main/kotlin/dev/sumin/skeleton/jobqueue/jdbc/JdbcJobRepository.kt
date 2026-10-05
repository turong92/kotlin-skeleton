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
    fun insert(type: String, payloadJson: String, runAt: Instant, maxAttempts: Int, now: Instant, logContext: String? = null): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """insert into skeleton_jobs
               (job_type, payload_json, status, attempts, max_attempts, next_run_at, created_at, updated_at, log_context)
               values (:type, :payload, 'PENDING', 0, :maxAttempts, :runAt, :now, :now, :logContext)""",
        )
            .param("type", type).param("payload", payloadJson).param("maxAttempts", maxAttempts).param("logContext", logContext)
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

    /**
     * 종료 갱신은 **내가 아직 이 잡의 임대 주인일 때만** 반영한다 — `status = 'RUNNING'` · `locked_by` · `attempts` 가 모두 내 청구와 같아야 한다.
     * 스테일 복구로 PENDING 이 되어 다른 워커가 다시 청구한 잡을 옛 워커가 늦게 DONE · RETRY · DEAD 로 덮어쓰지 못한다. 반영했으면 true.
     * (같은 워커가 다시 청구해도 attempts 가 올라가 있으므로 옛 청구의 결과는 거절된다)
     */
    fun markDone(id: Long, workerId: String, attempts: Int, now: Instant): Boolean =
        jdbc.sql(
            """update skeleton_jobs set status = 'DONE', locked_by = null, locked_at = null, updated_at = :now
               where id = :id and status = 'RUNNING' and locked_by = :worker and attempts = :attempts""",
        ).param("id", id).param("worker", workerId).param("attempts", attempts).param("now", dialect.instantParam(now)).update() > 0

    fun markRetry(id: Long, workerId: String, attempts: Int, nextRunAt: Instant, error: String, now: Instant): Boolean =
        jdbc.sql(
            """update skeleton_jobs
               set status = 'PENDING', next_run_at = :next, last_error = :error, locked_by = null, locked_at = null, updated_at = :now
               where id = :id and status = 'RUNNING' and locked_by = :worker and attempts = :attempts""",
        ).param("id", id).param("worker", workerId).param("attempts", attempts)
            .param("next", dialect.instantParam(nextRunAt)).param("error", error.take(4000)).param("now", dialect.instantParam(now)).update() > 0

    fun markDead(id: Long, workerId: String, attempts: Int, error: String, now: Instant): Boolean =
        jdbc.sql(
            """update skeleton_jobs
               set status = 'DEAD', last_error = :error, locked_by = null, locked_at = null, updated_at = :now
               where id = :id and status = 'RUNNING' and locked_by = :worker and attempts = :attempts""",
        ).param("id", id).param("worker", workerId).param("attempts", attempts)
            .param("error", error.take(4000)).param("now", dialect.instantParam(now)).update() > 0

    /**
     * 묶음에서 이 잡의 차례가 왔을 때 임대를 새로 잡는다 — 청구 시각은 묶음 전체가 같아서, 앞 잡들이 오래 걸리면 뒤 잡은 돌기도 전에
     * 스테일 복구 기준을 넘는다. 이미 복구되어 남에게 넘어갔으면(0행) false — 부르는 쪽은 그 잡을 돌리지 않는다.
     */
    fun renew(id: Long, workerId: String, attempts: Int, now: Instant): Boolean =
        jdbc.sql(
            """update skeleton_jobs set locked_at = :now, updated_at = :now
               where id = :id and status = 'RUNNING' and locked_by = :worker and attempts = :attempts""",
        ).param("id", id).param("worker", workerId).param("attempts", attempts).param("now", dialect.instantParam(now)).update() > 0

    /**
     * 워커가 죽어 RUNNING 으로 남은 잡을 되돌린다. attempts 는 이미 올라가 있으므로 그대로 둔다.
     * 시도를 다 쓴 잡(`attempts >= max_attempts`)은 PENDING 이 아니라 **DEAD** — 워커를 죽이는 독 작업이 영영 되살아나지 않게.
     * 그렇게 DEAD 가 된 잡은 [StaleRecovery.dead] 로 돌려준다 (`UPDATE … RETURNING` 은 MySQL 에 없어 한 트랜잭션에서 고르고 갱신한다).
     */
    fun recoverStale(lockedBefore: Instant, now: Instant): StaleRecovery =
        transactions.execute {
            val staleIds = jdbc.sql(
                """select id from skeleton_jobs
                   where status = 'RUNNING' and locked_at < :before
                   order by id
                   for update skip locked""",
            ).param("before", dialect.instantParam(lockedBefore)).query(Long::class.java).list()
            if (staleIds.isEmpty()) return@execute StaleRecovery(0, emptyList())
            val deadIds = jdbc.sql("select id from skeleton_jobs where id in (:ids) and attempts >= max_attempts")
                .param("ids", staleIds).query(Long::class.java).list()
            val dead = if (deadIds.isEmpty()) emptyList() else {
                jdbc.sql(
                    """update skeleton_jobs
                       set status = 'DEAD', locked_by = null, locked_at = null, updated_at = :now,
                           last_error = coalesce(last_error, 'stale RUNNING job exhausted its attempts')
                       where id in (:ids)""",
                ).param("now", dialect.instantParam(now)).param("ids", deadIds).update()
                jdbc.sql("select * from skeleton_jobs where id in (:ids) order by id").param("ids", deadIds).query(::map).list()
            }
            val reviveIds = staleIds - deadIds.toSet()
            if (reviveIds.isNotEmpty()) {
                jdbc.sql(
                    """update skeleton_jobs
                       set status = 'PENDING', locked_by = null, locked_at = null, updated_at = :now
                       where id in (:ids)""",
                ).param("now", dialect.instantParam(now)).param("ids", reviveIds).update()
            }
            StaleRecovery(reviveIds.size, dead)
        } ?: StaleRecovery(0, emptyList())

    /** [status] 줄 중 `updated_at` 이 [before] 보다 오래된 것을 [batchSize] 개씩 나눠 지운다. 지운 수를 돌려준다 */
    fun deleteFinishedBefore(status: JobStatus, before: Instant, batchSize: Int): Int {
        var total = 0
        while (true) {
            val ids = jdbc.sql(
                "select id from skeleton_jobs where status = :status and updated_at < :before order by id limit :limit",
            ).param("status", status.name).param("before", dialect.instantParam(before)).param("limit", batchSize)
                .query(Long::class.java).list()
            if (ids.isEmpty()) return total
            total += jdbc.sql("delete from skeleton_jobs where id in (:ids) and status = :status")
                .param("ids", ids).param("status", status.name).update()
        }
    }

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
        logContext = rs.getString("log_context"),
    )
}

/** [JdbcJobRepository.recoverStale] 결과 — [recovered] 는 PENDING 으로 되돌린 수, [dead] 는 시도를 다 써서 DEAD 로 보낸 잡 */
data class StaleRecovery(val recovered: Int, val dead: List<Job>)
