package dev.sumin.skeleton.event.kafka

import dev.sumin.skeleton.json.JacksonJsonCodec
import dev.sumin.skeleton.json.JsonCodec
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaOperations

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
    @ConditionalOnMissingBean
    fun kafkaEventPayloadSerializer(jsonCodec: ObjectProvider<JsonCodec>): KafkaEventPayloadSerializer =
        KafkaEventPayloadSerializer(jsonCodec.ifAvailable ?: JacksonJsonCodec())

    @Bean
    @ConditionalOnMissingBean(KafkaEventSender::class)
    fun kafkaEventSender(
        properties: EventKafkaProperties,
        kafkaOperations: ObjectProvider<KafkaOperations<String, ByteArray>>,
        payloadSerializer: KafkaEventPayloadSerializer,
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
            payloadSerializer = payloadSerializer,
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
}

class EventKafkaConfigurationException(message: String) : RuntimeException(message)
