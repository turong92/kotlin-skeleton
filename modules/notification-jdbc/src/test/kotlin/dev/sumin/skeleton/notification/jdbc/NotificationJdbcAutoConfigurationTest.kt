package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.notification.NotificationMuteRepository
import dev.sumin.skeleton.notification.advertising.AdvertisingNoticeStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import javax.sql.DataSource

class NotificationJdbcAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JsonAutoConfiguration::class.java,
                NotificationJdbcAutoConfiguration::class.java,
                NotificationAutoConfiguration::class.java,
            ),
        )
        .withBean(DataSource::class.java, {
            EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build()
        })
        .withBean(SqlDialect::class.java, { FakeDialect("h2") })

    @Test
    fun `creates jdbc inbox repository before notification core fallback`() {
        contextRunner.run { context ->
            val repositories = context.getBeansOfType(NotificationInboxRepository::class.java)

            assertEquals(1, repositories.size)
            assertIs<JdbcNotificationInboxRepository>(repositories.values.single())
            assertIs<JdbcNotificationMuteRepository>(context.getBean(NotificationMuteRepository::class.java))
        }
    }

    @Test
    fun `provides the reconfirm notice store for apps without push, and backs off for a store that is already there`() {
        val withAds = contextRunner.withConfiguration(AutoConfigurations.of(NotificationJdbcAdvertisingAutoConfiguration::class.java))
        withAds.run { context -> assertIs<JdbcAdvertisingNoticeStore>(context.getBean(AdvertisingNoticeStore::class.java)) }

        val other = object : AdvertisingNoticeStore {
            override fun isNoticed(accountId: String, type: String, agreedAt: java.time.Instant) = false
            override fun markNoticed(accountId: String, type: String, agreedAt: java.time.Instant, at: java.time.Instant) {}
        }
        withAds.withBean(AdvertisingNoticeStore::class.java, { other }).run { context ->
            assertEquals(1, context.getBeansOfType(AdvertisingNoticeStore::class.java).size)
            assertEquals(other, context.getBean(AdvertisingNoticeStore::class.java))
        }
    }
}

private class FakeDialect(override val vendor: String) : SqlDialect {
    override fun instantParam(value: java.time.Instant?): Any? = value
    override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = null
    override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>) = ""
}
