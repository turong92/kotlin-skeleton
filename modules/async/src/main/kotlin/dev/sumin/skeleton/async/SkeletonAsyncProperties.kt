package dev.sumin.skeleton.async

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.async")
data class SkeletonAsyncProperties(
    val enabled: Boolean = true,
    val corePoolSize: Int = 4,
    val maxPoolSize: Int = 16,
    val queueCapacity: Int = 100,
    val threadNamePrefix: String = "skeleton-async-",
    val awaitTerminationSeconds: Int = 20,
    val waitForTasksToCompleteOnShutdown: Boolean = true,
)
