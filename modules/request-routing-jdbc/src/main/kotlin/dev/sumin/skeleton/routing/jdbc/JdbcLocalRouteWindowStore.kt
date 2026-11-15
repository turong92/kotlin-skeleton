package dev.sumin.skeleton.routing.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.routing.LocalRouteWindowStore
import dev.sumin.skeleton.routing.LocalWindow
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder

/**
 * 로컬 창 표 `routing_local_windows` — 줄은 지우지 않는다(이력이 곧 기록). 여는 쪽 트랜잭션(`LocalWindowService`)이 닫기 + 넣기를 한 묶음으로 만든다.
 * 「열린」 줄 = `closed_at` 이 비고 `open_until` 이 지금보다 뒤인 줄.
 */
class JdbcLocalRouteWindowStore(private val jdbc: JdbcClient, private val dialect: SqlDialect) : LocalRouteWindowStore {
    override fun findOpen(now: Instant): LocalWindow? =
        jdbc.sql("select $COLUMNS from routing_local_windows where closed_at is null and open_until > :now order by id desc limit 1")
            .param("now", dialect.instantParam(now))
            .query { rs, _ -> window(rs) }
            .optional().orElse(null)

    override fun open(openedBy: String, reason: String, now: Instant, openUntil: Instant): LocalWindow {
        closeAll(openedBy, now)
        val keys = GeneratedKeyHolder()
        jdbc.sql("insert into routing_local_windows (opened_by, reason, opened_at, open_until) values (:openedBy, :reason, :openedAt, :openUntil)")
            .param("openedBy", openedBy).param("reason", reason)
            .param("openedAt", dialect.instantParam(now)).param("openUntil", dialect.instantParam(openUntil))
            .update(keys, "id")
        return LocalWindow(keys.key!!.toLong(), openedBy, reason, now, openUntil, null, null)
    }

    override fun closeAll(closedBy: String, now: Instant): List<Long> {
        val ids = jdbc.sql("select id from routing_local_windows where closed_at is null and open_until > :now order by id")
            .param("now", dialect.instantParam(now))
            .query { rs, _ -> rs.getLong("id") }.list()
        if (ids.isNotEmpty()) {
            jdbc.sql("update routing_local_windows set closed_by = :closedBy, closed_at = :now where id in (:ids)")
                .param("closedBy", closedBy).param("now", dialect.instantParam(now)).param("ids", ids).update()
        }
        return ids
    }

    private fun window(rs: ResultSet) = LocalWindow(
        id = rs.getLong("id"),
        openedBy = rs.getString("opened_by"),
        reason = rs.getString("reason"),
        openedAt = dialect.readInstant(rs, "opened_at")!!,
        openUntil = dialect.readInstant(rs, "open_until")!!,
        closedBy = rs.getString("closed_by"),
        closedAt = dialect.readInstant(rs, "closed_at"),
    )

    private companion object {
        const val COLUMNS = "id, opened_by, reason, opened_at, open_until, closed_by, closed_at"
    }
}
