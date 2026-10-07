package dev.sumin.skeleton.account.jdbc

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * "한 방향" 보증을 보는 도구 — 현재 데이터베이스의 **모든 표의 모든 열**에서 심어 둔 값(이메일 · 이름 · 제공자 주체 · IP · UA)을 찾는다.
 * 모듈 표 목록을 손으로 적지 않으므로 새 표가 생겨도 자동으로 훑는다 (마이그레이션 이력 표만 뺀다). PostgreSQL · MySQL 둘 다.
 */
object PlantedDataScan {
    /** "표.열" 목록 — 비어 있어야 한다 */
    fun find(jdbc: NamedParameterJdbcTemplate, vendor: String, planted: Collection<String>): List<String> {
        val schemaFilter = if (vendor == "mysql") "table_schema = database()" else "table_schema = current_schema()"
        val columns = jdbc.query(
            "select table_name, column_name from information_schema.columns where $schemaFilter and table_name not like 'flyway%' order by table_name, ordinal_position",
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }
        val hits = mutableListOf<String>()
        for ((table, column) in columns) {
            val q = if (vendor == "mysql") "`" else "\""
            val text = if (vendor == "mysql") "cast($q$column$q as char)" else "cast($q$column$q as text)"
            for (value in planted) {
                val n = jdbc.queryForObject(
                    "select count(*) from $q$table$q where $text like :v",
                    mapOf("v" to "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"), Int::class.java,
                ) ?: 0
                if (n > 0) hits += "$table.$column ($value)"
            }
        }
        return hits
    }
}
