package dev.sumin.skeleton.event.kafka

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.event-kafka")
data class EventKafkaProperties(
    val enabled: Boolean = false,
    val environment: String = "local",
    val topicPrefix: String? = null,
    val producer: Producer = Producer(),
    val consumer: Consumer = Consumer(),
) {
    data class Producer(
        val enabled: Boolean = true,
        val partitionKeyStrategy: KafkaEventPartitionKeyStrategyName =
            KafkaEventPartitionKeyStrategyName.EXPLICIT_OR_EVENT_ID,
    )

    data class Consumer(
        val enabled: Boolean = false,
    )

    internal fun resolvedTopicPrefix(): String {
        val explicitPrefix = topicPrefix?.trim()
        if (explicitPrefix != null) {
            return explicitPrefix
        }

        val normalizedEnvironment = environment.trim()
        return if (normalizedEnvironment.isBlank()) {
            ""
        } else {
            "$normalizedEnvironment."
        }
    }
}

enum class KafkaEventPartitionKeyStrategyName {
    NONE,
    EVENT_ID,
    EVENT_TYPE,
    TOPIC,
    EXPLICIT_OR_EVENT_ID,
}
