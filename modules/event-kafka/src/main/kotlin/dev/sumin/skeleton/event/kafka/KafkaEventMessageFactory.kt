package dev.sumin.skeleton.event.kafka

import dev.sumin.skeleton.common.TraceIdFilter
import org.slf4j.MDC

class KafkaEventMessageFactory(
    private val properties: EventKafkaProperties,
    private val partitionKeyStrategy: KafkaEventPartitionKeyStrategy,
) {
    fun toMessage(event: KafkaEvent): KafkaEventMessage =
        KafkaEventMessage(
            topic = resolveTopic(event.topic),
            key = partitionKeyStrategy.partitionKey(event),
            payload = event.payload,
            headers = standardHeaders(event),
            event = event,
        )

    private fun resolveTopic(topic: String): String =
        "${properties.resolvedTopicPrefix()}${topic.trim()}"

    private fun standardHeaders(event: KafkaEvent): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        headers.putAll(event.headers.filterValues { it.isNotBlank() })
        headers[KafkaEventHeaders.EVENT_TYPE] = event.type
        headers[KafkaEventHeaders.ENVIRONMENT] = properties.environment
        headers[KafkaEventHeaders.EVENT_ID] = event.id
        MDC.get(TraceIdFilter.MDC_KEY)?.takeIf { it.isNotBlank() }?.let { traceId ->
            headers[KafkaEventHeaders.TRACE_ID] = traceId
        }
        MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY)?.takeIf { it.isNotBlank() }?.let { spanId ->
            headers[KafkaEventHeaders.SPAN_ID] = spanId
        }
        return headers
    }
}

class ConfiguredKafkaEventPartitionKeyStrategy(
    private val properties: EventKafkaProperties,
) : KafkaEventPartitionKeyStrategy {
    override fun partitionKey(event: KafkaEvent): String? =
        when (properties.producer.partitionKeyStrategy) {
            KafkaEventPartitionKeyStrategyName.NONE -> null
            KafkaEventPartitionKeyStrategyName.EVENT_ID -> event.id
            KafkaEventPartitionKeyStrategyName.EVENT_TYPE -> event.type
            KafkaEventPartitionKeyStrategyName.TOPIC -> event.topic
            KafkaEventPartitionKeyStrategyName.EXPLICIT_OR_EVENT_ID -> event.partitionKey.normalized() ?: event.id
        }

    private fun String?.normalized(): String? =
        this?.trim()?.takeIf { it.isNotBlank() }
}
