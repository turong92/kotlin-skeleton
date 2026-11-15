package dev.sumin.skeleton.audit.jdbc

import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/** 요청 한 건의 출처 — 운영자가 어디서 눌렀는지. 요청 밖(작업 · 시작 시)이면 없다 */
data class RequestOrigin(val ip: String?, val userAgent: String?)

fun interface AuditOrigin {
    fun current(): RequestOrigin?

    companion object {
        val NONE = AuditOrigin { null }
    }
}

/**
 * 운영 기록 표 `operator_audit_log` — **더하기만 한다**(수정 · 30일 안의 삭제는 트리거가 막는다). 기록은 호출자가 연 트랜잭션이 있으면 거기에 합류한다(같은 연결):
 * **쓰기 트랜잭션 안에서 부른 곳만** 행동과 함께 커밋 · 롤백된다. 트랜잭션 밖에서 부르는 곳(서비스 호출이 커밋된 뒤 컨트롤러가 따로 부르는 곳)은 줄이 따로 커밋되므로 기록이 실패해도 행동은 남는다 — 알려진 한계(`docs/modules/audit-jdbc.md`).
 */
class JdbcOperatorAuditLog(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val time: TimeProvider,
    private val origin: AuditOrigin = AuditOrigin.NONE,
) : OperatorAuditLog, AuditLogStore {
    override fun record(action: OperatorAction) {
        val from = origin.current()
        jdbc.update(
            "insert into operator_audit_log (occurred_at, actor_id, action, target_type, target_id, result, ip, user_agent, detail) " +
                "values (:at, :actor, :action, :type, :target, :result, :ip, :ua, :detail)",
            MapSqlParameterSource()
                .addValue("at", dialect.instantParam(time.now()))
                .addValue("actor", action.actorId.take(64))
                .addValue("action", action.action.take(100))
                .addValue("type", action.targetType.take(64))
                .addValue("target", action.targetId.take(128))
                .addValue("result", action.result.name)
                .addValue("ip", from?.ip?.take(64))
                .addValue("ua", from?.userAgent?.take(255))
                .addValue("detail", action.detail.takeIf { it.isNotEmpty() }?.let(JSON::writeValueAsString)),
        )
    }

    override fun find(query: AuditQuery): List<AuditEntry> {
        val where = mutableListOf<String>()
        val params = MapSqlParameterSource()
        fun filter(column: String, name: String, value: Any?, op: String = "=") {
            if (value == null) return
            where += "$column $op :$name"
            params.addValue(name, value)
        }
        filter("occurred_at", "from", query.from?.let(dialect::instantParam), ">=")
        filter("occurred_at", "to", query.to?.let(dialect::instantParam), "<")
        filter("actor_id", "actor", query.actorId)
        filter("target_type", "type", query.targetType)
        filter("target_id", "target", query.targetId)
        filter("id", "before", query.before, "<")
        params.addValue("limit", query.limit)
        return jdbc.query(
            "select id, occurred_at, actor_id, action, target_type, target_id, result, ip, user_agent, detail from operator_audit_log" +
                (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) + " order by id desc limit :limit",
            params,
        ) { rs, _ ->
            AuditEntry(
                rs.getLong("id"), dialect.readInstant(rs, "occurred_at")!!, rs.getString("actor_id"), rs.getString("action"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("result"), rs.getString("ip"), rs.getString("user_agent"),
                rs.getString("detail")?.let { JSON.readValue(it, DETAIL) }.orEmpty(),
            )
        }
    }

    /** 먼저 지울 id 를 읽고 그 id 만 지운다 — `delete … limit` 은 PostgreSQL 에 없고, 서브쿼리의 `limit` 은 MySQL 이 막는다 */
    override fun purgeBefore(cutoff: Instant, limit: Int): Int {
        val ids = jdbc.queryForList(
            "select id from operator_audit_log where occurred_at < :cutoff order by id limit :limit",
            mapOf("cutoff" to dialect.instantParam(cutoff), "limit" to limit), Long::class.java,
        )
        if (ids.isEmpty()) return 0
        return jdbc.update("delete from operator_audit_log where id in (:ids)", mapOf("ids" to ids))
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        val DETAIL = object : TypeReference<Map<String, String>>() {}
    }
}
