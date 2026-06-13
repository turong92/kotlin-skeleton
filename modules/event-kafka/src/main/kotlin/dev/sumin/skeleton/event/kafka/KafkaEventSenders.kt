package dev.sumin.skeleton.event.kafka

import dev.sumin.skeleton.json.JsonCodec
import java.nio.charset.StandardCharsets
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaOperations

class LoggingKafkaEventSender : KafkaEventSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: KafkaEventMessage) {
        log.info(
            "Kafka event publishing is disabled; would publish eventType={} eventId={} topic={} key={}",
            message.headers[KafkaEventHeaders.EVENT_TYPE],
            message.headers[KafkaEventHeaders.EVENT_ID],
            message.topic,
            message.key,
        )
    }
}

class SpringKafkaEventSender(
    private val kafkaOperations: KafkaOperations<String, ByteArray>,
    private val payloadSerializer: KafkaEventPayloadSerializer,
) : KafkaEventSender {
    override fun send(message: KafkaEventMessage) {
        val payload = payloadSerializer.serialize(message.payload)
        val record = if (message.key == null) {
            ProducerRecord(message.topic, payload)
        } else {
            ProducerRecord(message.topic, message.key, payload)
        }
        message.headers.forEach { (name, value) ->
            record.headers().add(name, value.toByteArray(StandardCharsets.UTF_8))
        }
        kafkaOperations.send(record)
    }
}

class KafkaEventPayloadSerializer(
    private val jsonCodec: JsonCodec,
) {
    fun serialize(payload: Any?): ByteArray {
        val json = if (payload == null) {
            "null"
        } else {
            jsonCodec.canonicalString(jsonCodec.toDocument(payload))
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }
}
