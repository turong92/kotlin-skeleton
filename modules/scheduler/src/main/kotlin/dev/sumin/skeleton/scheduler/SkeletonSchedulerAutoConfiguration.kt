package dev.sumin.skeleton.scheduler

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@AutoConfiguration
@EnableConfigurationProperties(SkeletonSchedulerProperties::class)
@ConditionalOnProperty(
    prefix = "skeleton.scheduler",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SkeletonSchedulerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun skeletonTaskScheduler(properties: SkeletonSchedulerProperties): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = properties.poolSize.coerceAtLeast(1)
            setThreadNamePrefix("skeleton-scheduler-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(properties.shutdownAwaitTermination.toSeconds().toInt())
        }

    @Bean
    @ConditionalOnMissingBean
    fun skeletonScheduleResolver(properties: SkeletonSchedulerProperties): SkeletonScheduleResolver =
        SkeletonScheduleResolver(properties)

    @Bean
    @ConditionalOnMissingBean
    fun skeletonSchedulerExecutionGuard(
        environment: Environment,
        properties: SkeletonSchedulerProperties,
    ): SkeletonSchedulerExecutionGuard =
        SkeletonSchedulerExecutionGuard(environment, properties)

    @Bean
    @ConditionalOnMissingBean
    fun skeletonScheduledLockManager(): SkeletonScheduledLockManager =
        NoopSkeletonScheduledLockManager

    @Bean
    @ConditionalOnMissingBean
    fun skeletonScheduledFailureHandler(): SkeletonScheduledFailureHandler =
        LoggingSkeletonScheduledFailureHandler()

    @Bean
    @ConditionalOnMissingBean
    fun skeletonScheduledTaskRegistrar(
        applicationContext: ConfigurableApplicationContext,
        taskScheduler: TaskScheduler,
        scheduleResolver: SkeletonScheduleResolver,
        executionGuard: SkeletonSchedulerExecutionGuard,
        lockManager: SkeletonScheduledLockManager,
        failureHandlers: ObjectProvider<SkeletonScheduledFailureHandler>,
        properties: SkeletonSchedulerProperties,
    ): SkeletonScheduledTaskRegistrar =
        SkeletonScheduledTaskRegistrar(
            applicationContext = applicationContext,
            taskScheduler = taskScheduler,
            scheduleResolver = scheduleResolver,
            executionGuard = executionGuard,
            lockManager = lockManager,
            failureHandlers = failureHandlers.orderedStream().toList(),
            properties = properties,
        )
}
