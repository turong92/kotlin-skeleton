package dev.sumin.skeleton.persistence

import dev.sumin.skeleton.TestcontainersConfiguration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import javax.sql.DataSource

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

    @Test
    fun `모듈 마이그레이션을 복사한 schema_sql 은 인라인 index 까지 만들고 재실행해도 깨지지 않는다`() {
        // [jooq ignore] 마커 사이의 index 절이 실제로 실행됐는지 (Spring ScriptUtils 는 블록 주석만 벗긴다)
        val indexes = jdbc.sql("select index_name from information_schema.statistics where table_schema = database() and table_name = 'skeleton_jobs' group by index_name")
            .query(String::class.java).list().toSet()
        assertEquals(setOf("PRIMARY", "idx_skeleton_jobs_claim", "idx_skeleton_jobs_running"), indexes)
        // Mode B 는 매 기동마다 schema.sql 을 다시 돌린다 — 두 번째 실행도 성공해야 한다
        ResourceDatabasePopulator(ClassPathResource("schema-sql-example/schema.sql")).execute(dataSource)
    }

    @Autowired
    lateinit var dataSource: DataSource
}
