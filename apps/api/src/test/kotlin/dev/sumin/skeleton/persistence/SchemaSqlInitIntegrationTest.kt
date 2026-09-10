package dev.sumin.skeleton.persistence

import dev.sumin.skeleton.TestcontainersConfiguration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Flyway 없는 조합: `spring.flyway.enabled=false` + `spring.sql.init.mode=always` + `schema.sql`.
 * flyway starter 가 classpath 에 있어도 꺼지고, schema.sql 이 실행된다 (docs/schema-management.md).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-sql-example/schema.sql",
        "skeleton.job-queue.enabled=false",
    ],
)
class SchemaSqlInitIntegrationTest {
    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `schema_sql 이 적용되고 flyway 는 돌지 않는다`() {
        jdbc.sql("insert into schema_sql_probe (name) values ('x')").update()
        assertEquals(1L, jdbc.sql("select count(*) from schema_sql_probe").query(Long::class.java).single())
        val flywayTables = jdbc.sql("select count(*) from information_schema.tables where table_schema = database() and table_name = 'flyway_schema_history'")
            .query(Long::class.java).single()
        assertEquals(0L, flywayTables)
    }
}
