package dev.sumin.skeleton.dashboard.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.dashboard.DashboardDailyRow
import dev.sumin.skeleton.dashboard.DashboardDailyStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import java.time.LocalDate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * `dashboard_daily`. 쓰기는 (날짜 · 출처 · 종류)마다 한 트랜잭션 안에서 **지우고 넣기**다 — 넘긴 행(과 적재기가 알린 종류 전부)이 그 (날짜 · 출처 · 종류)의 전부가 되므로, 다시 적재했을 때 이번 결과에서 빠진 차원의
 * 옛 줄이 남지 않는다. 넣기는 방언에 기대지 않는 두 문장(충돌하면 아무것도 하지 않는 insert[SqlDialect.insertIgnore] 다음 값을 덮어쓰는 update)이라 같은 키를 두 번 써도 한 줄이고,
 * 동시에 같은 키를 쓰는 두 인스턴스도 한 줄(기본키)이 된다.
 */
class JdbcDashboardDailyStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
    private val time: TimeProvider,
) : DashboardDailyStore {
    private val insertIgnore = dialect.insertIgnore("dashboard_daily", COLUMNS, listOf("day", "source", "kind", "dimension"))

    override fun replace(day: LocalDate, source: String, kinds: Collection<String>, rows: List<DashboardDailyRow>) {
        val replaced = (kinds + rows.map { it.kind }).toSet()
        if (replaced.isEmpty()) return
        val now = dialect.instantParam(time.now())
        val params = rows.map {
            MapSqlParameterSource().addValue("day", it.day).addValue("source", it.source).addValue("kind", it.kind)
                .addValue("dimension", it.dimension).addValue("value", it.value).addValue("updated_at", now)
        }.toTypedArray()
        val deletes = replaced.map { mapOf("day" to day, "source" to source, "kind" to it) }.toTypedArray()
        tx.executeWithoutResult {
            jdbc.batchUpdate("delete from dashboard_daily where day = :day and source = :source and kind = :kind", deletes)
            if (params.isNotEmpty()) {
                jdbc.batchUpdate(insertIgnore, params)
                jdbc.batchUpdate(
                    "update dashboard_daily set value = :value, updated_at = :updated_at where day = :day and source = :source and kind = :kind and dimension = :dimension",
                    params,
                )
            }
        }
    }

    override fun read(source: String, kind: String, fromDay: LocalDate, toDay: LocalDate): List<DashboardDailyRow> =
        jdbc.query(
            "select day, dimension, value from dashboard_daily where source = :source and kind = :kind and day >= :from and day <= :to order by day, dimension",
            mapOf("source" to source, "kind" to kind, "from" to fromDay, "to" to toDay),
        ) { rs, _ -> DashboardDailyRow(rs.getObject("day", LocalDate::class.java), source, kind, rs.getString("dimension"), rs.getLong("value")) }

    override fun lastIngested(source: String): Instant? =
        jdbc.query("select max(updated_at) as last from dashboard_daily where source = :source", mapOf("source" to source)) { rs, _ -> dialect.readInstant(rs, "last") }.firstOrNull()

    private companion object {
        val COLUMNS = listOf("day", "source", "kind", "dimension", "value", "updated_at")
    }
}
