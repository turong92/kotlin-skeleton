package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.token.InMemoryOneTimeTokenStore
import dev.sumin.skeleton.account.token.OneTimeTokenStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource

class AccountJdbcAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class Infra {
        @Bean fun dataSource(): DataSource = DriverManagerDataSource("jdbc:h2:mem:accounts;MODE=PostgreSQL")
        @Bean fun tx(ds: DataSource) = DataSourceTransactionManager(ds)
        @Bean fun dialect(): SqlDialect = object : SqlDialect {
            override val vendor = "postgresql"
            override fun instantParam(value: java.time.Instant?): Any? = value
            override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = null
            override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
        }
    }

    private val configurations = AutoConfigurations.of(AccountJdbcAutoConfiguration::class.java, AccountAutoConfiguration::class.java)
    private val runner = ApplicationContextRunner().withConfiguration(configurations).withPropertyValues("skeleton.account.password.bcrypt-strength=4")

    @Test
    fun `the jdbc stores win over the in-memory defaults when a DataSource exists`() {
        runner.withUserConfiguration(Infra::class.java).run { ctx ->
            assertTrue(ctx.getBean(AccountRepository::class.java) is JdbcAccountRepository)
            assertTrue(ctx.getBean(OneTimeTokenStore::class.java) is JdbcOneTimeTokenStore)
            assertTrue(!ctx.containsBean("jdbcAccountAuditListener"), "audit is opt-in")
        }
    }

    @Test
    fun `audit is written only when switched on`() {
        runner.withUserConfiguration(Infra::class.java).withPropertyValues("skeleton.account.audit.enabled=true").run { ctx ->
            assertTrue(ctx.getBeansOfType(AccountEventListener::class.java).values.any { it is JdbcAccountAuditListener })
        }
    }

    @Test
    fun `without a DataSource the in-memory defaults stay`() {
        runner.run { ctx ->
            assertTrue(ctx.getBean(AccountRepository::class.java) is InMemoryAccountRepository)
            assertTrue(ctx.getBean(OneTimeTokenStore::class.java) is InMemoryOneTimeTokenStore)
            assertEquals(1, ctx.getBeansOfType(AccountRepository::class.java).size)
        }
    }
}
