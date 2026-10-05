package dev.sumin.skeleton.auth.sessions.jdbc

import dev.sumin.skeleton.auth.sessions.AuthSessionAutoConfiguration
import dev.sumin.skeleton.auth.sessions.InMemorySessionStore
import dev.sumin.skeleton.auth.sessions.SessionStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import javax.sql.DataSource

class AuthSessionJdbcAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class Infra {
        @Bean fun dataSource(): DataSource = DriverManagerDataSource("jdbc:h2:mem:sessions;MODE=PostgreSQL")
        @Bean fun tx(ds: DataSource) = DataSourceTransactionManager(ds)
        @Bean fun dialect(): SqlDialect = object : SqlDialect {
            override val vendor = "postgresql"
            override fun instantParam(value: java.time.Instant?): Any? = value
            override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = null
            override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
        }
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(Infra::class.java)
        .withConfiguration(AutoConfigurations.of(AuthSessionJdbcAutoConfiguration::class.java, AuthSessionAutoConfiguration::class.java))

    @Test
    fun `the jdbc store wins over the in-memory default when a DataSource exists`() {
        runner.run { ctx ->
            assertTrue(ctx.getBean(SessionStore::class.java) is JdbcSessionStore)
        }
    }

    @Test
    fun `without a DataSource the in-memory default stays`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthSessionJdbcAutoConfiguration::class.java, AuthSessionAutoConfiguration::class.java))
            .run { ctx -> assertTrue(ctx.getBean(SessionStore::class.java) is InMemorySessionStore) }
    }
}
