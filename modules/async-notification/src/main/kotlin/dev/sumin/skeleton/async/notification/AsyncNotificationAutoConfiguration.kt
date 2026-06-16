package dev.sumin.skeleton.async.notification

import dev.sumin.skeleton.async.SkeletonAsyncAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationPublisher
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [SkeletonAsyncAutoConfiguration::class, NotificationAutoConfiguration::class])
@EnableConfigurationProperties(AsyncNotificationProperties::class)
@ConditionalOnProperty(
    prefix = "skeleton.async-notification",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AsyncNotificationAutoConfiguration {
    @Bean
    @ConditionalOnBean(NotificationPublisher::class)
    @ConditionalOnMissingBean(AsyncUncaughtExceptionHandler::class)
    fun asyncNotificationExceptionHandler(
        publisher: NotificationPublisher,
        properties: AsyncNotificationProperties,
    ): AsyncNotificationExceptionHandler =
        AsyncNotificationExceptionHandler(publisher, properties)
}
