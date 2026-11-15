package dev.sumin.skeleton.appcontrol.jdbc

import dev.sumin.skeleton.appcontrol.AppControlEntry
import dev.sumin.skeleton.appcontrol.AppControlStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionOperations

/**
 * 표 하나(`app_control_entries`). 읽기는 전체 한 번(`order by name`), 쓰기는 「없으면 넣고(두 방언 공통 insert-ignore) 이어서 덮어쓴다」 -
 * 처음 만드는 두 요청이 겹쳐도 오류 없이 마지막이 이긴다.
 */
class JdbcAppControlStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val transactions: TransactionOperations,
    private val dialect: SqlDialect,
) : AppControlStore {
    override fun <T> transaction(block: () -> T): T = transactions.execute { block() }!!

    override fun all(): List<AppControlEntry> =
        jdbc.query("select name, payload, updated_by, updated_at from app_control_entries order by name") { rs, _ ->
            AppControlEntry(rs.getString("name"), rs.getString("payload"), rs.getString("updated_by"), dialect.readInstant(rs, "updated_at")!!)
        }

    override fun put(entry: AppControlEntry) {
        val params = MapSqlParameterSource()
            .addValue("name", entry.name)
            .addValue("payload", entry.payload)
            .addValue("updated_by", entry.updatedBy)
            .addValue("updated_at", dialect.instantParam(entry.updatedAt))
        jdbc.update(dialect.insertIgnore("app_control_entries", listOf("name", "payload", "updated_by", "updated_at"), listOf("name")), params)
        jdbc.update("update app_control_entries set payload = :payload, updated_by = :updated_by, updated_at = :updated_at where name = :name", params)
    }

    override fun delete(name: String): Boolean =
        jdbc.update("delete from app_control_entries where name = :name", mapOf("name" to name)) > 0
}
