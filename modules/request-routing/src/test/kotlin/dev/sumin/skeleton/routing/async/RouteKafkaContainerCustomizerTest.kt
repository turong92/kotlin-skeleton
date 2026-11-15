package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.routing.RequestRoutingProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.RecordInterceptor

class RouteKafkaContainerCustomizerTest {
    private val consumer = org.apache.kafka.clients.consumer.MockConsumer<Any, Any>("earliest")

    private fun container(groupId: String? = "orders-consumers"): ConcurrentMessageListenerContainer<Any, Any> {
        val props = ContainerProperties("orders")
        groupId?.let { props.setGroupId(it) }
        return ConcurrentMessageListenerContainer(DefaultKafkaConsumerFactory<Any, Any>(mapOf("bootstrap.servers" to "localhost:1")), props)
    }

    private fun customizer(instance: String) =
        RouteKafkaContainerCustomizer(RequestRoutingProperties(enabled = true, service = "orders", instance = instance))

    @Test
    fun `candidateGetsSuffixedGroupAndLatest`() {
        val c = container()
        customizer("candidate").configure(c)
        assertEquals("orders-consumers-candidate", c.containerProperties.groupId)
        assertEquals("latest", c.containerProperties.kafkaConsumerProperties["auto.offset.reset"])
        assertNotNull(c.recordInterceptor)
    }

    @Test
    fun `baselineKeepsGroup`() {
        val c = container()
        customizer("").configure(c)
        assertEquals("orders-consumers", c.containerProperties.groupId)
        assertNull(c.containerProperties.kafkaConsumerProperties["auto.offset.reset"])
        assertNotNull(c.recordInterceptor)
    }

    @Test
    fun `keepsExistingInterceptor`() {
        val c = container()
        val seen = mutableListOf<Long>()
        val existing = object : RecordInterceptor<Any, Any> {
            override fun intercept(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>): ConsumerRecord<Any, Any>? {
                seen += record.offset()
                return record
            }
        }
        c.setRecordInterceptor(existing)
        customizer("candidate").configure(c)
        val marked = ConsumerRecord<Any, Any>("orders", 0, 7L, "k", "v").also { it.headers().add("baggage", "route=orders:candidate".toByteArray()) }
        assertSame(marked, c.recordInterceptor!!.intercept(marked, consumer))
        assertEquals(listOf(7L), seen)
    }

    @Test
    fun `a record the route interceptor skips never reaches the existing interceptor`() {
        val c = container()
        var reached = false
        c.setRecordInterceptor(object : RecordInterceptor<Any, Any> {
            override fun intercept(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>): ConsumerRecord<Any, Any>? {
                reached = true
                return record
            }
        })
        customizer("candidate").configure(c)
        assertNull(c.recordInterceptor!!.intercept(ConsumerRecord<Any, Any>("orders", 0, 1L, "k", "v"), consumer))
        assertEquals(false, reached)
    }

    @Test
    fun `candidate without a known group id refuses to share the original group`() {
        assertFailsWith<IllegalStateException> { customizer("candidate").configure(container(groupId = null)) }
    }
}
