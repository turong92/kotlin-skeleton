package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.video.VideoWakeRuns
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** `video_wake_runs` - 깨우기 기록. 시각은 [SqlDialect.instantParam] 으로 묶어 두 방언이 같은 순서를 본다 */
class JdbcVideoWakeRuns(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : VideoWakeRuns {
    override fun record(at: Instant, ok: Boolean, execution: String?, error: String?) {
        jdbc.update(
            "insert into video_wake_runs (woke_at, ok, execution, error) values (:at, :ok, :execution, :error)",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("ok", ok).addValue("execution", execution?.take(MAX_TEXT)).addValue("error", error?.take(MAX_TEXT)),
        )
    }

    override fun okSince(since: Instant): Int =
        jdbc.queryForObject("select count(*) from video_wake_runs where ok = :ok and woke_at >= :since", MapSqlParameterSource().addValue("ok", true).addValue("since", dialect.instantParam(since)), Int::class.java)!!

    override fun lastOkAt(): Instant? =
        jdbc.query("select max(woke_at) as last_at from video_wake_runs where ok = :ok", MapSqlParameterSource().addValue("ok", true)) { rs, _ -> dialect.readInstant(rs, "last_at") }.firstOrNull()

    override fun consecutiveFailures(): Int =
        jdbc.queryForObject(
            "select count(*) from video_wake_runs where ok = :bad and id > coalesce((select max(id) from video_wake_runs where ok = :good), 0)",
            MapSqlParameterSource().addValue("bad", false).addValue("good", true),
            Int::class.java,
        )!!

    override fun deleteOlderThan(before: Instant): Int =
        jdbc.update("delete from video_wake_runs where woke_at < :before", MapSqlParameterSource().addValue("before", dialect.instantParam(before)))

    private companion object {
        const val MAX_TEXT = 200
    }
}
