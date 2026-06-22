package dev.sumin.skeleton.event.outbox.jdbc

import dev.sumin.skeleton.event.kafka.EventKafkaAutoConfiguration
import dev.sumin.skeleton.event.kafka.KafkaEventMessageFactory
import dev.sumin.skeleton.event.kafka.KafkaEventPublisher
import dev.sumin.skeleton.event.kafka.KafkaEventSender
import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.json.JsonCodec
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@AutoConfiguration(
    after = [
        DataSourceAutoConfiguration::class,
        JsonAutoConfiguration::class,
    ],
    before = [EventKafkaAutoConfiguration::class],
)
@EnableConfigurationProperties(EventOutboxJdbcProperties::class)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnProperty(
    prefix = "skeleton.event-outbox-jdbc",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class EventOutboxJdbcAutoConfiguration {
    @Bean
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun jdbcOutboxEventRepository(
        dataSource: DataSource,
        jsonCodec: JsonCodec,
    ): OutboxEventRepository =
        JdbcOutboxEventRepository(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            jsonCodec = jsonCodec,
        )

    @Bean
    @ConditionalOnBean(OutboxEventRepository::class)
    @ConditionalOnMissingBean(KafkaEventPublisher::class)
    fun outboxKafkaEventPublisher(repository: OutboxEventRepository): KafkaEventPublisher =
        OutboxKafkaEventPublisher(repository)

    @Bean
    @ConditionalOnBean(OutboxEventRepository::class)
    @ConditionalOnMissingBean
    fun outboxEventDispatcher(
        repository: OutboxEventRepository,
        sender: KafkaEventSender,
        messageFactory: KafkaEventMessageFactory,
        properties: EventOutboxJdbcProperties,
    ): OutboxEventDispatcher =
        OutboxEventDispatcher(
            repository = repository,
            sender = sender,
            messageFactory = messageFactory,
            properties = properties,
        )
}
