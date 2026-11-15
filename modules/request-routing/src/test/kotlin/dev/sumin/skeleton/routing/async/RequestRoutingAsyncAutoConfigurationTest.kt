package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.jobqueue.jdbc.JobLanes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.config.ContainerCustomizer

class RequestRoutingAsyncAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RequestRoutingJobQueueAutoConfiguration::class.java, RequestRoutingKafkaAutoConfiguration::class.java))

    @Test
    fun `nothing is registered while routing is off`() {
        runner.run { ctx ->
            assertEquals(0, ctx.getBeansOfType(JobLanes::class.java).size)
            assertEquals(0, ctx.getBeansOfType(ContainerCustomizer::class.java).size)
        }
    }

    @Test
    fun `lanes and the kafka customizer are registered when routing is on`() {
        runner.withPropertyValues("skeleton.routing.enabled=true", "skeleton.routing.service=orders").run { ctx ->
            assertIs<RoutingJobLanes>(ctx.getBean(JobLanes::class.java))
            assertIs<RouteKafkaContainerCustomizer>(ctx.getBean("routeKafkaContainerCustomizer"))
        }
    }

    @Test
    fun `an app's own job lanes or container customizer wins`() {
        val own = object : JobLanes {
            override fun enqueueLane(): String? = null
            override fun workerLane(): String? = null
        }
        runner.withPropertyValues("skeleton.routing.enabled=true")
            .withBean(JobLanes::class.java, { own })
            .withBean("appCustomizer", ContainerCustomizer::class.java, { ContainerCustomizer<Any, Any, org.springframework.kafka.listener.ConcurrentMessageListenerContainer<Any, Any>> { } })
            .run { ctx ->
                assertEquals(own, ctx.getBean(JobLanes::class.java))
                assertEquals(false, ctx.containsBean("routeKafkaContainerCustomizer"))
            }
    }
}
