package dev.sumin.skeleton.persistence.postgresql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** `timestamptz` ↔ UTC `OffsetDateTime`. pgjdbc 는 `Instant` 를 바인딩하지 못하고 UTC `LocalDateTime` 은 세션 시간대로 해석한다. */
class PostgresSqlDialect : SqlDialect {
    override val vendor: String = "postgresql"

    override fun instantParam(value: Instant?): Any? = value?.atOffset(ZoneOffset.UTC)

    override fun readInstant(rs: ResultSet, column: String): Instant? =
        rs.getObject(column, OffsetDateTime::class.java)?.toInstant()

    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String =
        "insert into $table (${columns.joinToString()}) values (${columns.joinToString { ":$it" }}) " +
            "on conflict (${conflictColumns.joinToString()}) do nothing"
}
