package dev.sumin.skeleton.persistence.mysql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * `datetime(6)` ↔ UTC 벽시계 `LocalDateTime`. Connector/J 는 `OffsetDateTime`/`Timestamp` 를 JVM 시간대 벽시계로 저장하므로
 * (실측 2026-10-01) 원문이 UTC 로 남는 건 UTC `LocalDateTime` 뿐이다.
 * insertIgnore 는 `insert ignore` 대신 `on duplicate key update` — `ignore` 는 잘림 같은 다른 오류까지 경고로 삼킨다.
 */
class MySqlSqlDialect : SqlDialect {
    override val vendor: String = "mysql"

    override fun instantParam(value: Instant?): Any? = value?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }

    override fun readInstant(rs: ResultSet, column: String): Instant? =
        rs.getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String {
        val noop = conflictColumns.first()
        return "insert into $table (${columns.joinToString()}) values (${columns.joinToString { ":$it" }}) " +
            "on duplicate key update $noop = $noop"
    }
}
