package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationInboxRepository
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

    @Test
    fun `creates jdbc inbox repository before notification core fallback`() {
        contextRunner.run { context ->
            val repositories = context.getBeansOfType(NotificationInboxRepository::class.java)

            assertEquals(1, repositories.size)
            assertIs<JdbcNotificationInboxRepository>(repositories.values.single())
        }
    }
}
