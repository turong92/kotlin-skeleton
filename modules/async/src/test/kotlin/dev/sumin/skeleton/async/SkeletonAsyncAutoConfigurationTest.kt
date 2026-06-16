package dev.sumin.skeleton.async

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.lang.reflect.Method
import java.util.function.Supplier

class SkeletonAsyncAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SkeletonAsyncAutoConfiguration::class.java,
                SkeletonAsyncConfigurerAutoConfiguration::class.java,
            ),
        )

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

    @Test
    fun `default async configurer uses supplied uncaught exception handler`() {
        val handler = RecordingAsyncUncaughtExceptionHandler()
        val exception = IllegalStateException("boom")
        val method = Supplier::class.java.getMethod("get")

        contextRunner
            .withBean(AsyncUncaughtExceptionHandler::class.java, Supplier { handler })
            .run { context ->
                val configurer = context.getBean(AsyncConfigurer::class.java)
                val exceptionHandler = requireNotNull(configurer.asyncUncaughtExceptionHandler)

                exceptionHandler.handleUncaughtException(exception, method, "arg")

                assertThat(handler.exception).isSameAs(exception)
                assertThat(handler.method).isSameAs(method)
                assertThat(handler.params).containsExactly("arg")
            }
    }

    @Test
    fun `default async configurer backs off when custom async configurer exists`() {
        val customConfigurer = object : AsyncConfigurer {}

        contextRunner
            .withBean(AsyncConfigurer::class.java, Supplier { customConfigurer })
            .run { context ->
                assertThat(context).hasSingleBean(AsyncConfigurer::class.java)
                assertThat(context.getBean(AsyncConfigurer::class.java)).isSameAs(customConfigurer)
                assertThat(context).hasBean("skeletonAsyncTaskExecutor")
            }
    }

    private class RecordingAsyncUncaughtExceptionHandler : AsyncUncaughtExceptionHandler {
        lateinit var exception: Throwable
        lateinit var method: Method
        lateinit var params: Array<out Any?>

        override fun handleUncaughtException(
            ex: Throwable,
            method: Method,
            vararg params: Any?,
        ) {
            this.exception = ex
            this.method = method
            this.params = params
        }
    }
}
