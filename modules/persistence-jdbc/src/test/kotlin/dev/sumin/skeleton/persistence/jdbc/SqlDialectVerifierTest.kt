package dev.sumin.skeleton.persistence.jdbc

import java.sql.ResultSet
import java.time.Instant
import java.util.function.Supplier
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class SqlDialectVerifierTest {
    class FakeDialect(override val vendor: String) : SqlDialect {
        override fun instantParam(value: Instant?): Any? = value
        override fun readInstant(rs: ResultSet, column: String): Instant? = null
        override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
    }

    private val h2: Supplier<DataSource> = Supplier {
        EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build()
    }
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SqlDialectAutoConfiguration::class.java))

    @Test
    fun `no dialect module fails with the modules to add`() {
        runner.withBean(DataSource::class.java, h2).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("modules:db-postgresql").hasMessageContaining("modules:db-mysql")
        }
    }

    @Test
    fun `two dialect modules fail`() {
        runner.withBean(DataSource::class.java, h2)
            .withBean("a", SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("h2") })
            .withBean("b", SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("h2") })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause().hasMessageContaining("exactly one")
            }
    }

    @Test
    fun `dialect that does not match the connected database fails at startup`() {
        runner.withBean(DataSource::class.java, h2)
            .withBean(SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("postgresql") })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageContaining("postgresql").hasMessageContaining("h2")
            }
    }

    @Test
    fun `matching dialect starts`() {
        runner.withBean(DataSource::class.java, h2)
            .withBean(SqlDialect::class.java, Supplier<SqlDialect> { FakeDialect("h2") })
            .run { context -> assertThat(context).hasNotFailed().hasSingleBean(SqlDialectVerifier::class.java) }
    }

    @Test
    fun `app without a DataSource is not checked`() {
        runner.run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(SqlDialectVerifier::class.java) }
    }
}
