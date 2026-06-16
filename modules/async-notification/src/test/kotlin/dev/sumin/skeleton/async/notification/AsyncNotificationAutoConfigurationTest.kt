package dev.sumin.skeleton.async.notification

import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationPublisher
import java.lang.reflect.Method
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class AsyncNotificationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                NotificationAutoConfiguration::class.java,
                AsyncNotificationAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `auto configuration creates async uncaught exception handler by default`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(AsyncUncaughtExceptionHandler::class.java)
            assertThat(context).hasSingleBean(AsyncNotificationExceptionHandler::class.java)
        }
    }

    @Test
    fun `auto configuration backs off when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.async-notification.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(AsyncUncaughtExceptionHandler::class.java)
                assertThat(context).doesNotHaveBean(AsyncNotificationExceptionHandler::class.java)
            }
    }

    @Test
    fun `auto configuration backs off when custom handler exists`() {
        val customHandler = NoopAsyncUncaughtExceptionHandler()

        contextRunner
            .withBean(AsyncUncaughtExceptionHandler::class.java, Supplier { customHandler })
            .run { context ->
                assertThat(context).hasSingleBean(AsyncUncaughtExceptionHandler::class.java)
                assertThat(context).doesNotHaveBean(AsyncNotificationExceptionHandler::class.java)
                assertThat(context.getBean(AsyncUncaughtExceptionHandler::class.java)).isSameAs(customHandler)
            }
    }

    private class NoopAsyncUncaughtExceptionHandler : AsyncUncaughtExceptionHandler {
        override fun handleUncaughtException(
            ex: Throwable,
            method: Method,
            vararg params: Any?,
        ) = Unit
    }
}
