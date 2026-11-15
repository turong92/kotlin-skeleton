package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.routing.RequestRoutingProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.BatchMessageListener
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener

// 이름이 `…Container(` 로 끝나면 Testcontainers 규칙 시험이 컨테이너 생성으로 오인한다
private typealias ListenerBox = ConcurrentMessageListenerContainer<Any, Any>

/** 꾸미개가 실제 컨테이너에 달렸는지 — 앱이 자기 팩토리를 두면 Boot 가 꾸미개를 달아 주지 않는다 */
class RouteKafkaListenerCheckTest {
    private fun listener(batch: Boolean = false, autoStartup: Boolean = true): ListenerBox {
        val props = ContainerProperties("orders").apply {
            setGroupId("orders-consumers")
            setMessageListener(if (batch) BatchMessageListener<Any, Any> { _: List<ConsumerRecord<Any, Any>> -> } else MessageListener<Any, Any> { })
        }
        return ListenerBox(DefaultKafkaConsumerFactory<Any, Any>(mapOf("bootstrap.servers" to "localhost:1")), props).also { it.isAutoStartup = autoStartup }
    }

    private fun properties(instance: String) = RequestRoutingProperties(enabled = true, service = "orders", instance = instance)

    private fun problems(instance: String, vararg containers: ListenerBox) =
        RouteKafkaListenerCheck(properties(instance)) { containers.toList() }.problems()

    @Test
    fun `a container the customizer reached passes`() {
        val c = listener().also { RouteKafkaContainerCustomizer(properties("candidate")).configure(it) }
        assertEquals(emptyList(), problems("candidate", c))
    }

    @Test
    fun `a container from an app factory that never got the customizer fails on the original too`() {
        val problem = problems("", listener()).single()
        assertTrue("record interceptor" in problem, problem)
    }

    @Test
    fun `a candidate container still on the original group fails`() {
        val c = listener().also { RouteKafkaContainerCustomizer(properties("")).configure(it) }
        assertTrue("consumer group" in problems("candidate", c).single())
    }

    @Test
    fun `a candidate batch listener fails because record filtering does not reach it`() {
        val c = listener(batch = true).also { RouteKafkaContainerCustomizer(properties("candidate")).configure(it) }
        assertTrue("batch" in problems("candidate", c).single())
    }

    @Test
    fun `containers that do not auto start are not checked`() {
        assertEquals(emptyList(), problems("candidate", listener(autoStartup = false)))
    }

    @Test
    fun `start throws with every problem`() {
        val check = RouteKafkaListenerCheck(properties("candidate")) { listOf(listener()) }
        assertFailsWith<IllegalStateException> { check.start() }
    }
}
