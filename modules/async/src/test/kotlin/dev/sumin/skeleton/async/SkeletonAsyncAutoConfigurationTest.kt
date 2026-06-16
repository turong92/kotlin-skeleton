package dev.sumin.skeleton.async

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

class SkeletonAsyncAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SkeletonAsyncAutoConfiguration::class.java))

    @Test
    fun `auto configuration creates context propagating executor by default`() {
        contextRunner
            .withPropertyValues(
                "skeleton.async.core-pool-size=2",
                "skeleton.async.max-pool-size=8",
                "skeleton.async.queue-capacity=11",
                "skeleton.async.thread-name-prefix=work-",
            )
            .run { context ->
                assertThat(context).hasSingleBean(AsyncContextTaskDecorator::class.java)
                assertThat(context).hasBean("skeletonAsyncTaskExecutor")
                assertThat(context).hasSingleBean(AsyncConfigurer::class.java)

                val executor = context.getBean("skeletonAsyncTaskExecutor", ThreadPoolTaskExecutor::class.java)
                assertThat(executor.corePoolSize).isEqualTo(2)
                assertThat(executor.maxPoolSize).isEqualTo(8)
                assertThat(executor.threadNamePrefix).isEqualTo("work-")
            }
    }

    @Test
    fun `auto configuration backs off when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.async.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(AsyncContextTaskDecorator::class.java)
                assertThat(context).doesNotHaveBean("skeletonAsyncTaskExecutor")
                assertThat(context).doesNotHaveBean(AsyncConfigurer::class.java)
            }
    }
}
