package dev.sumin.skeleton.async

import java.lang.reflect.Method
import java.util.concurrent.Executor
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.AsyncConfigurer

@AutoConfiguration(
    after = [SkeletonAsyncAutoConfiguration::class],
    before = [TaskExecutionAutoConfiguration::class],
)
@ConditionalOnProperty(prefix = "skeleton.async", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SkeletonAsyncConfigurerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AsyncConfigurer::class)
    fun skeletonAsyncConfigurer(
        @Qualifier("skeletonAsyncTaskExecutor")
        executorProvider: ObjectProvider<Executor>,
        handlerProvider: ObjectProvider<AsyncUncaughtExceptionHandler>,
    ): AsyncConfigurer =
        object : AsyncConfigurer {
            override fun getAsyncExecutor(): Executor? =
                executorProvider.getIfAvailable()

            override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
                DelegatingAsyncUncaughtExceptionHandler(handlerProvider)
        }
}

private class DelegatingAsyncUncaughtExceptionHandler(
    private val handlerProvider: ObjectProvider<AsyncUncaughtExceptionHandler>,
) : AsyncUncaughtExceptionHandler {
    private val fallback = SimpleAsyncUncaughtExceptionHandler()

    override fun handleUncaughtException(
        ex: Throwable,
        method: Method,
        vararg params: Any?,
    ) {
        val handler = handlerProvider.getIfAvailable() ?: fallback
        handler.handleUncaughtException(ex, method, *params)
    }
}
