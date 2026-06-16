package dev.sumin.skeleton.async

import java.util.concurrent.Executor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@AutoConfiguration
@EnableAsync
@EnableConfigurationProperties(SkeletonAsyncProperties::class)
@ConditionalOnProperty(prefix = "skeleton.async", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SkeletonAsyncAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun asyncContextTaskDecorator(): AsyncContextTaskDecorator =
        AsyncContextTaskDecorator()

    @Bean(name = ["skeletonAsyncTaskExecutor"])
    @ConditionalOnMissingBean(name = ["skeletonAsyncTaskExecutor"])
    fun skeletonAsyncTaskExecutor(
        properties: SkeletonAsyncProperties,
        asyncContextTaskDecorator: AsyncContextTaskDecorator,
    ): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.corePoolSize.coerceAtLeast(1)
            maxPoolSize = properties.maxPoolSize.coerceAtLeast(corePoolSize)
            queueCapacity = properties.queueCapacity.coerceAtLeast(0)
            setThreadNamePrefix(properties.threadNamePrefix)
            setTaskDecorator(asyncContextTaskDecorator)
            setWaitForTasksToCompleteOnShutdown(properties.waitForTasksToCompleteOnShutdown)
            setAwaitTerminationSeconds(properties.awaitTerminationSeconds.coerceAtLeast(0))
        }

    @Bean
    @ConditionalOnMissingBean(AsyncConfigurer::class)
    fun skeletonAsyncConfigurer(
        skeletonAsyncTaskExecutor: ThreadPoolTaskExecutor,
    ): AsyncConfigurer =
        object : AsyncConfigurer {
            override fun getAsyncExecutor(): Executor =
                skeletonAsyncTaskExecutor
        }
}
