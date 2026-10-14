package dev.sumin.skeleton.persistence.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectDatasource
import java.sql.ResultSet
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.mock.env.MockEnvironment

class ProjectPoolsTest {
    private class Dialect(override val vendor: String, override val schemaIsDatabase: Boolean = false) : SqlDialect {
        override fun instantParam(value: Instant?): Any? = value
        override fun readInstant(rs: ResultSet, column: String): Instant? = null
        override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
    }

    private val details = object : JdbcConnectionDetails {
        override fun getUsername() = "common"
        override fun getPassword() = "common-pw"
        override fun getJdbcUrl() = "jdbc:postgresql://localhost:1/app"
    }

    private fun pool(project: Project, dialect: SqlDialect, env: MockEnvironment = MockEnvironment()) =
        ProjectPools.create(project, details, dialect, env) as HikariDataSource

    private fun project(ds: ProjectDatasource = ProjectDatasource()) = Project("a", listOf("a.test"), "proj_a", ds, emptyMap())

    @Test
    fun `a pool is lazy, small, leaves the schema to the account default and uses the common account unless the project has its own`() {
        val p = pool(project(), Dialect("postgresql"))
        assertThat(p.isRunning).isFalse()
        assertThat(p.schema).isNull()   // 기본 스키마는 계정에 박힌다 (ProjectAccountsVerifier) — 연결 단위 SET 에 기대지 않는다
        assertThat(p.catalog).isNull()
        assertThat(p.minimumIdle).isZero()
        assertThat(p.maximumPoolSize).isEqualTo(3)
        assertThat(p.username).isEqualTo("common")
        assertThat(p.jdbcUrl).isEqualTo("jdbc:postgresql://localhost:1/app")

        val own = pool(project(ProjectDatasource("proj_a_user", "own-pw", 5)), Dialect("postgresql"))
        assertThat(own.username).isEqualTo("proj_a_user")
        assertThat(own.password).isEqualTo("own-pw")
        assertThat(own.maximumPoolSize).isEqualTo(5)
    }

    @Test
    fun `where the schema is a database the pool selects it as the catalog`() {
        val p = pool(project(), Dialect("mysql", schemaIsDatabase = true))
        assertThat(p.catalog).isEqualTo("proj_a")
        assertThat(p.schema).isNull()
    }

    @Test
    fun `the shared spring datasource hikari settings still apply - the mysql module puts the utc session settings there`() {
        val env = MockEnvironment().withProperty("spring.datasource.hikari.data-source-properties.connectionTimeZone", "UTC")
        assertThat(pool(project(), Dialect("postgresql"), env).dataSourceProperties.getProperty("connectionTimeZone")).isEqualTo("UTC")
    }
}
