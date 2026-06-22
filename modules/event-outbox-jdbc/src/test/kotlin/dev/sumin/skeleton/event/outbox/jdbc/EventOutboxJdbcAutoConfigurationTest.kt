package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.EventKafkaAutoConfiguration
import dev.sumin.skeleton.event.kafka.KafkaEventPublisher
import dev.sumin.skeleton.json.JsonAutoConfiguration
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class EventOutboxJdbcAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JsonAutoConfiguration::class.java,
                EventOutboxJdbcAutoConfiguration::class.java,
                EventKafkaAutoConfiguration::class.java,
            ),
        )
        .withBean(DataSource::class.java, {
            EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build()
        })

    @Test
    fun `creates outbox repository dispatcher and publisher override`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(OutboxEventRepository::class.java)
            assertThat(context).hasSingleBean(OutboxEventDispatcher::class.java)
            assertThat(context).hasSingleBean(KafkaEventPublisher::class.java)
            assertThat(context.getBean(KafkaEventPublisher::class.java))
                .isInstanceOf(OutboxKafkaEventPublisher::class.java)
        }
    }

    @Test
    fun `disabled outbox keeps event kafka publisher fallback`() {
        contextRunner
            .withPropertyValues("skeleton.event-outbox-jdbc.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(OutboxEventRepository::class.java)
                assertThat(context).doesNotHaveBean(OutboxEventDispatcher::class.java)
                assertThat(context.getBean(KafkaEventPublisher::class.java))
                    .isNotInstanceOf(OutboxKafkaEventPublisher::class.java)
            }
    }
}
