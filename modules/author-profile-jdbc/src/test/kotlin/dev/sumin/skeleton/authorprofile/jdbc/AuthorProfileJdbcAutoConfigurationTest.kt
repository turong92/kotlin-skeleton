package dev.sumin.skeleton.authorprofile.jdbc

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.authorprofile.AuthorProfileAutoConfiguration
import dev.sumin.skeleton.authorprofile.AuthorProfileRepository
import dev.sumin.skeleton.authorprofile.AuthorProfileService
import dev.sumin.skeleton.authorprofile.CapInsert
import dev.sumin.skeleton.authorprofile.RenameResult
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager

class AuthorProfileJdbcAutoConfigurationTest {
    private val dataSource: DataSource = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build()

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthorProfileJdbcAutoConfiguration::class.java))
        .withBean(DataSource::class.java, { dataSource })
        .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(dataSource) })
        .withBean(SqlDialect::class.java, { FakeDialect })

    @Test
    fun `the repository port is the JDBC implementation`() {
        runner.run { ctx -> assertIs<JdbcAuthorProfileRepository>(ctx.getBean(AuthorProfileRepository::class.java)) }
    }

    @Test
    fun `an app repository replaces the JDBC one`() {
        val own = object : AuthorProfileRepository by NoRepository {}
        runner.withBean(AuthorProfileRepository::class.java, { own }).run { ctx ->
            assertEquals(1, ctx.getBeansOfType(AuthorProfileRepository::class.java).size)
            assertEquals(own, ctx.getBean(AuthorProfileRepository::class.java))
        }
    }

    @Test
    fun `without a DataSource the adapter steps aside`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AuthorProfileJdbcAutoConfiguration::class.java)).run { ctx ->
            assertEquals(0, ctx.getBeansOfType(AuthorProfileRepository::class.java).size)
        }
    }

    @Test
    fun `the module's auto-configuration finds the adapter whichever order they are listed in - the service and the directory start on it`() {
        for (jdbcFirst in listOf(true, false)) {
            val configs = if (jdbcFirst) listOf(AuthorProfileJdbcAutoConfiguration::class.java, AuthorProfileAutoConfiguration::class.java, AccountAutoConfiguration::class.java)
            else listOf(AccountAutoConfiguration::class.java, AuthorProfileAutoConfiguration::class.java, AuthorProfileJdbcAutoConfiguration::class.java)
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(*configs.toTypedArray()))
                .withPropertyValues("skeleton.account.password.bcrypt-strength=4")
                .withBean(DataSource::class.java, { dataSource })
                .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(dataSource) })
                .withBean(SqlDialect::class.java, { FakeDialect })
                .run { ctx ->
                    assertNull(ctx.startupFailure, "jdbcFirst=$jdbcFirst")
                    assertIs<JdbcAuthorProfileRepository>(ctx.getBean(AuthorProfileRepository::class.java))
                    ctx.getBean(AuthorProfileService::class.java)
                    assertEquals("AuthorProfileDirectory", ctx.getBean(AuthorDirectory::class.java).javaClass.simpleName)
                }
        }
    }

    @Test
    fun `the author-profile module names this adapter's auto-configuration in its afterName`() {
        val afterName = AuthorProfileAutoConfiguration::class.java.getAnnotation(org.springframework.boot.autoconfigure.AutoConfiguration::class.java).afterName.toList()
        assertEquals(listOf(AuthorProfileJdbcAutoConfiguration::class.java.name), afterName)
    }
}

private object NoRepository : AuthorProfileRepository {
    private fun nope(): Nothing = error("not used")
    override fun find(id: String) = nope()
    override fun findAll(ids: Collection<String>) = nope()
    override fun findActive(accountId: String) = nope()
    override fun findAllOf(accountId: String) = nope()
    override fun countActive(accountId: String) = nope()
    override fun findPrimary(accountId: String) = nope()
    override fun tagsOf(key: String) = nope()
    override fun primariesByKey(key: String) = nope()
    override fun countCreatedSince(accountId: String, since: java.time.Instant) = nope()
    override fun insert(profile: dev.sumin.skeleton.authorprofile.AuthorProfile) = nope()
    override fun insertWithinCap(profile: dev.sumin.skeleton.authorprofile.AuthorProfile, maxActive: Int, createLimit: Int, since: java.time.Instant?): CapInsert = nope()
    override fun rename(id: String, accountId: String, name: String, key: String, tag: String?, now: java.time.Instant): RenameResult = nope()
    override fun markDeleted(id: String, accountId: String, now: java.time.Instant) = nope()
    override fun markAdopted(accountId: String, now: java.time.Instant) = nope()
    override fun deleteByAccount(accountId: String) = nope()
}

private object FakeDialect : SqlDialect {
    override val vendor = "h2"
    override fun instantParam(value: java.time.Instant?): Any? = value
    override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = null
    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
}
