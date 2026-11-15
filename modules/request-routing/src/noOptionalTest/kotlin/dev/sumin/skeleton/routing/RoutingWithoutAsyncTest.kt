package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.time.TimeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 작업 큐 · spring-kafka 가 클래스패스에 없어도(compileOnly) 모듈은 기동한다 — 차선 · Kafka 꾸미개는 서지 않는다 */
class RoutingWithoutAsyncTest {
    @Test
    fun `boots without the job queue or spring-kafka on the classpath`() {
        assertTrue(runCatching { Class.forName("dev.sumin.skeleton.jobqueue.jdbc.JobLanes") }.isFailure)
        assertTrue(runCatching { Class.forName("org.springframework.kafka.listener.ConcurrentMessageListenerContainer") }.isFailure)
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    RequestRoutingAutoConfiguration::class.java,
                    dev.sumin.skeleton.routing.async.RequestRoutingJobQueueAutoConfiguration::class.java,
                    dev.sumin.skeleton.routing.async.RequestRoutingKafkaAutoConfiguration::class.java,
                ),
            )
            .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })
            .withPropertyValues("skeleton.routing.enabled=true", "skeleton.routing.service=orders", "skeleton.routing.instance=candidate")
            .run { ctx ->
                assertEquals(null, ctx.startupFailure)
                assertTrue(ctx.containsBean("routeTargets"))
                assertTrue(!ctx.containsBean("routeKafkaContainerCustomizer"))
            }
    }
}
