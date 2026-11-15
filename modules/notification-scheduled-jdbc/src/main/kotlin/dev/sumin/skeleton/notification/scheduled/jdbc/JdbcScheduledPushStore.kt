package dev.sumin.skeleton.notification.scheduled.jdbc

import dev.sumin.skeleton.notification.scheduled.DuePush
import dev.sumin.skeleton.notification.scheduled.ScheduleResult
import dev.sumin.skeleton.notification.scheduled.ScheduledPush
import dev.sumin.skeleton.notification.scheduled.ScheduledPushStats
import dev.sumin.skeleton.notification.scheduled.ScheduledPushStatus
import dev.sumin.skeleton.notification.scheduled.ScheduledPushStore
import dev.sumin.skeleton.notification.scheduled.SkipReason
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Duration
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 표 `scheduled_pushes`(예약 한 통 = 한 줄, 기본 키 = 앱이 정한 키). 상태를 바꾸는 문장은 모두 `where status = 'PENDING' …` 조건부 갱신이라
 * 여러 인스턴스가 같은 줄을 만져도 하나만 1 줄을 갱신한다 - 잠금 · 트랜잭션 없이 한 번만 집힌다. 호출자의 트랜잭션이 있으면 거기에 참여한다.
 */
class JdbcScheduledPushStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : ScheduledPushStore {
    private val insertIgnore = dialect.insertIgnore(TABLE, INSERT_COLUMNS, listOf("push_key"))

    override fun upsert(push: ScheduledPush, now: Instant): ScheduleResult {
        val params = values(push, now)
        val replaced = jdbc.update(
            """
            update scheduled_pushes set recipient_id = :recipient_id, run_at = :run_at, title = :title, body = :body, link = :link, advertising = :advertising, updated_at = :now
            where push_key = :push_key and status = 'PENDING' and (claimed_until is null or claimed_until < :now)
            """.trimIndent(),
            params,
        )
        if (replaced > 0) return ScheduleResult.REPLACED
        // 이미 나갔거나 · 건너뛰었거나 · 지금 집혀 나가는 중이면 그대로 둔다. 없을 때만 넣는다(동시에 둘이 넣어도 하나만 남는다)
        val exists = jdbc.queryForObject("select count(*) from scheduled_pushes where push_key = :k", mapOf("k" to push.key), Long::class.java)!! > 0
        if (exists) return ScheduleResult.IGNORED
        jdbc.update(insertIgnore, params)
        return ScheduleResult.CREATED
    }

    override fun cancel(key: String): Boolean =
        jdbc.update("delete from scheduled_pushes where push_key = :k and status = 'PENDING'", mapOf("k" to key)) > 0

    override fun claimDue(now: Instant, lease: Duration, limit: Int): List<DuePush> {
        val candidates = jdbc.queryForList(
            """
            select push_key from scheduled_pushes
            where status = 'PENDING' and run_at <= :now and (claimed_until is null or claimed_until < :now)
            order by run_at limit :limit
            """.trimIndent(),
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("limit", limit),
            String::class.java,
        )
        val until = dialect.instantParam(now.plus(lease))
        val won = candidates.filter { key ->
            jdbc.update(
                """
                update scheduled_pushes set claimed_until = :until, attempts = attempts + 1, updated_at = :now
                where push_key = :k and status = 'PENDING' and run_at <= :now and (claimed_until is null or claimed_until < :now)
                """.trimIndent(),
                MapSqlParameterSource().addValue("until", until).addValue("now", dialect.instantParam(now)).addValue("k", key),
            ) == 1
        }
        if (won.isEmpty()) return emptyList()
        return jdbc.query("select * from scheduled_pushes where push_key in (:keys) order by run_at", mapOf("keys" to won)) { rs, _ ->
            DuePush(
                ScheduledPush(
                    key = rs.getString("push_key"), recipientId = rs.getString("recipient_id"), runAt = dialect.readInstant(rs, "run_at")!!,
                    title = rs.getString("title"), body = rs.getString("body"), link = rs.getString("link"), advertising = rs.getBoolean("advertising"),
                ),
                rs.getInt("attempts"),
            )
        }
    }

    override fun finish(key: String, status: ScheduledPushStatus, reason: SkipReason?, now: Instant) {
        jdbc.update(
            "update scheduled_pushes set status = :status, skip_reason = :reason, claimed_until = null, finished_at = :now, updated_at = :now where push_key = :k and status = 'PENDING'",
            MapSqlParameterSource().addValue("status", status.name).addValue("reason", reason?.name).addValue("now", dialect.instantParam(now)).addValue("k", key),
        )
    }

    override fun reschedule(key: String, runAt: Instant, now: Instant) {
        jdbc.update(
            "update scheduled_pushes set run_at = :run_at, claimed_until = null, updated_at = :now where push_key = :k and status = 'PENDING'",
            MapSqlParameterSource().addValue("run_at", dialect.instantParam(runAt)).addValue("now", dialect.instantParam(now)).addValue("k", key),
        )
    }

    override fun sentCountSince(recipientId: String, since: Instant): Long =
        jdbc.queryForObject(
            "select count(*) from scheduled_pushes where recipient_id = :r and status = 'SENT' and finished_at >= :since",
            MapSqlParameterSource().addValue("r", recipientId).addValue("since", dialect.instantParam(since)),
            Long::class.java,
        )!!

    override fun deleteByRecipient(recipientId: String): Int =
        jdbc.update("delete from scheduled_pushes where recipient_id = :r", mapOf("r" to recipientId))

    override fun purgeFinishedBefore(cutoff: Instant): Int =
        jdbc.update(
            "delete from scheduled_pushes where status in ('SENT', 'SKIPPED') and finished_at < :cutoff",
            MapSqlParameterSource().addValue("cutoff", dialect.instantParam(cutoff)),
        )

    override fun stats(): ScheduledPushStats {
        val counts = jdbc.query("select status, count(*) as n from scheduled_pushes group by status", emptyMap<String, Any>()) { rs, _ -> rs.getString("status") to rs.getLong("n") }.toMap()
        return ScheduledPushStats(counts[ScheduledPushStatus.PENDING.name] ?: 0, counts[ScheduledPushStatus.SENT.name] ?: 0, counts[ScheduledPushStatus.SKIPPED.name] ?: 0)
    }

    private fun values(push: ScheduledPush, now: Instant) = MapSqlParameterSource()
        .addValue("push_key", push.key).addValue("recipient_id", push.recipientId).addValue("run_at", dialect.instantParam(push.runAt))
        .addValue("title", push.title).addValue("body", push.body).addValue("link", push.link).addValue("advertising", push.advertising)
        .addValue("status", ScheduledPushStatus.PENDING.name).addValue("attempts", 0)
        .addValue("created_at", dialect.instantParam(now)).addValue("updated_at", dialect.instantParam(now)).addValue("now", dialect.instantParam(now))

    private companion object {
        const val TABLE = "scheduled_pushes"
        val INSERT_COLUMNS = listOf("push_key", "recipient_id", "run_at", "title", "body", "link", "advertising", "status", "attempts", "created_at", "updated_at")
    }
}
