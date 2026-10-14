package dev.sumin.skeleton.migration.flyway

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectDatasource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class ProjectsMigrationStrategyTest {
    private val details = object : JdbcConnectionDetails {
        override fun getUsername() = db.username
        override fun getPassword() = db.password
        override fun getJdbcUrl() = db.jdbcUrl
    }

    private fun project(id: String, schema: String) = Project(id, listOf("$id.test"), schema, ProjectDatasource(), emptyMap())
    private fun jdbc() = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
    private fun count(sql: String) = jdbc().sql(sql).query(Int::class.java).single()

    /** Boot 가 만든 Flyway 대신 — 스크립트는 지금 것 그대로(스키마 이름 없는 `create table`)이고 어느 스키마에 만들지는 전략이 정한다 */
    private fun bootFlyway(dir: Path) = Flyway.configure().dataSource(db.jdbcUrl, db.username, db.password).locations("filesystem:$dir").load()

    @Test
    fun `every project schema gets the same scripts and its own history - and the schemas stay apart`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table t (id int); insert into t values (1);")

        ProjectsMigrationStrategy(listOf(project("a", "mp_a"), project("b", "mp_b")), details).migrate(bootFlyway(dir))

        assertEquals(1, count("select count(*) from mp_a.t"))
        assertEquals(1, count("select count(*) from mp_b.t"))
        assertEquals(1, count("select count(*) from mp_a.flyway_schema_history where version is not null"))
        assertEquals(1, count("select count(*) from mp_b.flyway_schema_history where version is not null"))
        jdbc().sql("insert into mp_a.t values (2)").update()
        assertEquals(2, count("select count(*) from mp_a.t"))
        assertEquals(1, count("select count(*) from mp_b.t"))
    }

    @Test
    fun `one failing schema fails the start - and says which project`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table t (id int);")
        jdbc().sql("create schema mp_bad").update()
        jdbc().sql("create table mp_bad.t (id int)").update()   // 이 스키마에는 이미 표가 있어 첫 스크립트가 깨진다

        val e = assertFailsWith<RuntimeException> {
            ProjectsMigrationStrategy(listOf(project("good", "mp_good"), project("bad", "mp_bad")), details).migrate(bootFlyway(dir))
        }
        assertTrue(e.message!!.contains("'bad'") && e.message!!.contains("mp_bad"), e.message)
    }

    companion object {
        val db = PostgreSQLContainer(DockerImageName.parse("postgres:18"))

        @JvmStatic @BeforeAll
        fun start() = db.start()

        @JvmStatic @AfterAll
        fun stop() = db.stop()
    }
}
