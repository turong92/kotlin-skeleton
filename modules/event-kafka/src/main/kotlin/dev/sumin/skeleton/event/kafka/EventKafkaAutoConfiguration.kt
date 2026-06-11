package dev.sumin.skeleton.event.kafka

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaOperations
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

@AutoConfiguration
@EnableConfigurationProperties(EventKafkaProperties::class)
class EventKafkaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun kafkaEventPublisher(applicationEventPublisher: ApplicationEventPublisher): KafkaEventPublisher =
        ApplicationKafkaEventPublisher(applicationEventPublisher)

    @Bean
    @ConditionalOnMissingBean
    fun kafkaEventPartitionKeyStrategy(properties: EventKafkaProperties): KafkaEventPartitionKeyStrategy =
        ConfiguredKafkaEventPartitionKeyStrategy(properties)

    @Bean
    @ConditionalOnMissingBean
    fun kafkaEventMessageFactory(
        properties: EventKafkaProperties,
        partitionKeyStrategy: KafkaEventPartitionKeyStrategy,
    ): KafkaEventMessageFactory =
        KafkaEventMessageFactory(properties, partitionKeyStrategy)

    @Bean
    @ConditionalOnMissingBean(KafkaEventSender::class)
    fun kafkaEventSender(
        properties: EventKafkaProperties,
        kafkaOperations: ObjectProvider<KafkaOperations<String, ByteArray>>,
        objectMapper: ObjectProvider<ObjectMapper>,
    ): KafkaEventSender {
        if (!properties.enabled) {
            return LoggingKafkaEventSender()
        }

        val operations = kafkaOperations.ifAvailable
            ?: throw EventKafkaConfigurationException(
                "Kafka event publishing is enabled but no KafkaOperations<String, ByteArray> bean is available.",
            )

        return SpringKafkaEventSender(
            kafkaOperations = operations,
            objectMapper = objectMapper.ifAvailable ?: defaultObjectMapper(),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.event-kafka.producer",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun kafkaEventPublishListener(
        sender: KafkaEventSender,
        messageFactory: KafkaEventMessageFactory,
    ): KafkaEventPublishListener =
        KafkaEventPublishListener(sender, messageFactory)

    private fun defaultObjectMapper(): ObjectMapper =
        JsonMapper.builder()
            .addModule(kotlinModule())
            .build()
}

class EventKafkaConfigurationException(message: String) : RuntimeException(message)
