package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.video.DisplayJob
import dev.sumin.skeleton.video.DisplayJobStore
import dev.sumin.skeleton.video.DisplayState
import dev.sumin.skeleton.video.VideoStatus
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * `video_display_jobs` - 움직이는 이미지 표시본 변환 일. 집기는 `FOR UPDATE SKIP LOCKED` 로 잠근 한 줄을 조건부 갱신한다(두 방언 같은 SQL).
 * 시각은 [SqlDialect.instantParam] 으로 묶는다. 새 줄은 [SqlDialect.insertIgnore] 라 같은 이미지는 한 줄이다
 */
class JdbcDisplayJobStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val transactions: TransactionTemplate,
) : DisplayJobStore {
    override fun enqueue(keyHash: String, objectKey: String, at: Instant) {
        jdbc.update(
            dialect.insertIgnore("video_display_jobs", listOf("key_hash", "object_key", "state", "attempts", "created_at", "updated_at"), listOf("key_hash")),
            MapSqlParameterSource().addValue("key_hash", keyHash).addValue("object_key", objectKey).addValue("state", DisplayState.QUEUED.name)
                .addValue("attempts", 0).addValue("created_at", dialect.instantParam(at)).addValue("updated_at", dialect.instantParam(at)),
        )
    }

    override fun find(keyHash: String): DisplayJob? =
        jdbc.query("select * from video_display_jobs where key_hash = :h", mapOf("h" to keyHash)) { rs, _ -> rs.toJob() }.firstOrNull()

    override fun claimNext(owner: String, leaseUntil: Instant, at: Instant): DisplayJob? = transactions.execute {
        val hash = jdbc.query("select key_hash from video_display_jobs where state = 'QUEUED' order by created_at, key_hash limit 1 for update skip locked", emptyMap<String, Any>()) { rs, _ -> rs.getString(1) }
            .firstOrNull() ?: return@execute null
        val claimed = jdbc.update(
            "update video_display_jobs set state = 'RUNNING', lease_owner = :owner, lease_until = :until, attempts = attempts + 1, updated_at = :at where key_hash = :h and state = 'QUEUED'",
            MapSqlParameterSource().addValue("owner", owner).addValue("until", dialect.instantParam(leaseUntil)).addValue("at", dialect.instantParam(at)).addValue("h", hash),
        )
        if (claimed == 1) find(hash) else null
    }

    override fun extendLease(keyHash: String, owner: String, until: Instant, at: Instant): Boolean =
        jdbc.update(
            "update video_display_jobs set lease_until = :until, updated_at = :at where key_hash = :h and state = 'RUNNING' and lease_owner = :owner",
            MapSqlParameterSource().addValue("until", dialect.instantParam(until)).addValue("at", dialect.instantParam(at)).addValue("h", keyHash).addValue("owner", owner),
        ) == 1

    override fun finish(keyHash: String, owner: String, at: Instant): Boolean =
        jdbc.update(
            "update video_display_jobs set state = 'DONE', lease_owner = null, lease_until = null, updated_at = :at where key_hash = :h and state = 'RUNNING' and lease_owner = :owner",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("h", keyHash).addValue("owner", owner),
        ) == 1

    override fun release(keyHash: String, owner: String?, retry: Boolean, code: String, at: Instant): Boolean =
        jdbc.update(
            "update video_display_jobs set state = ${nextState(retry)}, fail_code = :code, lease_owner = null, lease_until = null, updated_at = :at " +
                "where key_hash = :h and state = 'RUNNING'${if (owner != null) " and lease_owner = :owner" else ""}",
            MapSqlParameterSource().addValue("code", code.take(64)).addValue("at", dialect.instantParam(at)).addValue("h", keyHash).addValue("owner", owner).addValue("max", VideoStatus.MAX_ATTEMPTS),
        ) == 1

    override fun expireLeases(now: Instant): Int =
        jdbc.update(
            "update video_display_jobs set state = ${nextState(true)}, fail_code = 'LEASE_EXPIRED', lease_owner = null, lease_until = null, updated_at = :now where state = 'RUNNING' and lease_until < :now",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("max", VideoStatus.MAX_ATTEMPTS),
        )

    /** 다시 줄 세울 수 있으면 `QUEUED`, 아니면(다시 안 함 · 시도를 다 씀) `FAILED` */
    private fun nextState(retry: Boolean) = if (retry) "case when attempts < :max then 'QUEUED' else 'FAILED' end" else "'FAILED'"

    private fun ResultSet.toJob() = DisplayJob(
        keyHash = getString("key_hash"),
        objectKey = getString("object_key"),
        state = DisplayState.valueOf(getString("state")),
        attempts = getInt("attempts"),
        leaseOwner = getString("lease_owner"),
        leaseUntil = dialect.readInstant(this, "lease_until"),
    )
}
