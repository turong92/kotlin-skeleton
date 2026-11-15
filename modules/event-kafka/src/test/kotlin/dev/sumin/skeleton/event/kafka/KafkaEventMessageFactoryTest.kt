package dev.sumin.skeleton.event.kafka

import dev.sumin.skeleton.common.TraceIdFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class KafkaEventMessageFactoryTest {
    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `creates prefixed topic partition key and standard headers`() {
        MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")
        val properties = EventKafkaProperties(
            environment = "prod",
            producer = EventKafkaProperties.Producer(
                partitionKeyStrategy = KafkaEventPartitionKeyStrategyName.EXPLICIT_OR_EVENT_ID,
            ),
        )
        val factory = KafkaEventMessageFactory(
            properties = properties,
            partitionKeyStrategy = ConfiguredKafkaEventPartitionKeyStrategy(properties),
        )

        val message = factory.toMessage(
            KafkaEvent(
                topic = "orders",
                type = "order.created",
                id = "event-1",
                payload = mapOf("orderId" to "order-1"),
                partitionKey = "order-1",
                headers = mapOf("source" to "checkout"),
            ),
        )

        assertThat(message.topic).isEqualTo("prod.orders")
        assertThat(message.key).isEqualTo("order-1")
        assertThat(message.headers).containsAllEntriesOf(
            mapOf(
                "source" to "checkout",
                KafkaEventHeaders.EVENT_TYPE to "order.created",
                KafkaEventHeaders.ENVIRONMENT to "prod",
                KafkaEventHeaders.EVENT_ID to "event-1",
                KafkaEventHeaders.TRACE_ID to "4bf92f3577b34da6a3ce929d0e0e4736",
                KafkaEventHeaders.SPAN_ID to "00f067aa0ba902b7",
            ),
        )
    }

    @Test
    fun `uses event id as fallback partition key`() {
        val properties = EventKafkaProperties(environment = "dev")
        val factory = KafkaEventMessageFactory(
            properties = properties,
            partitionKeyStrategy = ConfiguredKafkaEventPartitionKeyStrategy(properties),
        )

        val message = factory.toMessage(
            KafkaEvent(
                topic = "orders",
                type = "order.created",
                id = "event-2",
                payload = emptyMap<String, Any?>(),
            ),
        )

        assertThat(message.key).isEqualTo("event-2")
    }

    private fun factory(): KafkaEventMessageFactory {
        val properties = EventKafkaProperties(environment = "dev")
        return KafkaEventMessageFactory(properties, ConfiguredKafkaEventPartitionKeyStrategy(properties))
    }

    private fun event(headers: Map<String, String> = emptyMap()) =
        KafkaEvent(topic = "orders", type = "order.created", id = "event-3", payload = emptyMap<String, Any?>(), headers = headers)

    @Test
    fun `adds baggage when route in mdc`() {
        MDC.put("route", "orders:alice")

        assertThat(factory().toMessage(event()).headers["baggage"]).isEqualTo("route=orders:alice")
    }

    @Test
    fun `no baggage without route`() {
        assertThat(factory().toMessage(event()).headers).doesNotContainKey("baggage")
    }

    @Test
    fun `a route member the app copied onto the event is removed when no route is in the mdc`() {
        val kept = factory().toMessage(event(mapOf("baggage" to "a=1,route=orders:candidate")))
        val alone = factory().toMessage(event(mapOf("baggage" to "route=orders:candidate")))

        assertThat(kept.headers["baggage"]).isEqualTo("a=1")
        assertThat(alone.headers).doesNotContainKey("baggage")
    }

    @Test
    fun `event header baggage keeps other members`() {
        MDC.put("route", "orders:alice")

        val message = factory().toMessage(event(mapOf("baggage" to "a=1")))

        assertThat(message.headers["baggage"]).isEqualTo("a=1,route=orders:alice")
    }
}
