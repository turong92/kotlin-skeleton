package dev.sumin.skeleton.persistence.jdbc

import javax.sql.DataSource
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.jdbc.DatabaseDriver

/** 기동 때 "방언 모듈 정확히 하나" 와 "연결된 DB 와 같은 방언" 을 확인한다. 틀리면 첫 쿼리가 아니라 여기서 실패한다. */
class SqlDialectVerifier(
    private val dataSource: DataSource,
    private val dialects: List<SqlDialect>,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val dialect = when (dialects.size) {
            0 -> throw IllegalStateException(
                "No SqlDialect. Add exactly one of implementation(project(\":modules:db-postgresql\")) " +
                    "or implementation(project(\":modules:db-mysql\")) to the app.",
            )
            1 -> dialects.single()
            else -> throw IllegalStateException(
                "Found ${dialects.size} SqlDialect beans (${dialects.joinToString { it.vendor }}). " +
                    "An app assembles exactly one of modules:db-postgresql / modules:db-mysql.",
            )
        }
        val connected = dataSource.connection.use { DatabaseDriver.fromJdbcUrl(it.metaData.url).id }
        check(connected == dialect.vendor) {
            "SqlDialect '${dialect.vendor}' does not match the connected database '$connected'. " +
                "Swap the db-* module or fix spring.datasource.url."
        }
    }
}
