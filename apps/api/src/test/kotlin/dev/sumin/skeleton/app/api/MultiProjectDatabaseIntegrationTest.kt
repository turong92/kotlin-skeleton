package dev.sumin.skeleton.app.api

import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 프로젝트 둘 — 같은 서버 · 다른 스키마. 마이그레이션이 스키마마다 돌고, 한 프로젝트의 문맥은 다른 프로젝트의 행을 읽지 못하고, 문맥 없는 DB 사용은 실패한다 */
@SpringBootTest(
    properties = [
        "skeleton.projects.a.hosts=a.localhost", "skeleton.projects.a.schema=mp_a", "skeleton.projects.a.datasource.username=mp_a", "skeleton.projects.a.datasource.password=mp_a-pw",
        "skeleton.projects.b.hosts=b.localhost", "skeleton.projects.b.schema=mp_b", "skeleton.projects.b.datasource.username=mp_b", "skeleton.projects.b.datasource.password=mp_b-pw",
        "skeleton.projects.old.hosts=old.localhost", "skeleton.projects.old.status=closed",
    ],
)
@Import(MultiProjectDatabaseIntegrationTest.Db::class)
class MultiProjectDatabaseIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Db {
        @Bean fun jdbcConnectionDetails(): JdbcConnectionDetails = db
    }

    companion object {
        val db: JdbcConnectionDetails by lazy { MultiProjectTestAccounts.newDatabase("mp_a", "mp_b") }
    }
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var registry: ProjectRegistry

    private fun inProject(id: String, block: () -> Unit) = ProjectContext.runIn(registry.projects.first { it.id == id }, block)

    @Test
    fun `every project schema has the module tables with their own migration history`() {
        listOf("a", "b").forEach { id ->
            inProject(id) {
                assertEquals(1, jdbc.sql("select count(*) from accounts where 1 = 0 or true limit 1").query(Int::class.java).list().size)
                assertEquals("mp_$id", jdbc.sql("select current_schema()").query(String::class.java).single())
                assertEquals(true, jdbc.sql("select count(*) from flyway_schema_history").query(Int::class.java).single() > 0)
            }
        }
    }

    @Test
    fun `a row written in one project is invisible in the other`() {
        inProject("a") { jdbc.sql("create table probe (v text)").update(); jdbc.sql("insert into probe values ('only-a')").update() }
        inProject("b") {
            assertEquals(0, jdbc.sql("select count(*) from information_schema.tables where table_schema = current_schema() and table_name = 'probe'").query(Int::class.java).single())
        }
        inProject("a") { assertEquals("only-a", jdbc.sql("select v from probe").query(String::class.java).single()) }
    }

    @Test
    fun `using the database without a project fails and does not fall to a default schema`() {
        assertFailsWith<Exception> { jdbc.sql("select 1").query(Int::class.java).single() }.also {
            generateSequence<Throwable>(it) { t -> t.cause }.any { t -> t is NoProjectContextException }.let { found -> assertEquals(true, found, "cause chain: $it") }
        }
    }

    @Test
    fun `a closed project has no pool and no schema - the database refuses to connect for it`() {
        val closed = registry.projects.first { it.id == "old" }
        assertFailsWith<Exception> { ProjectContext.runIn(closed) { jdbc.sql("select 1").query(Int::class.java).single() } }
        inProject("a") { assertEquals(0, jdbc.sql("select count(*) from information_schema.schemata where schema_name = 'old'").query(Int::class.java).single()) }
    }
}
