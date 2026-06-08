package dev.sumin.skeleton.notification

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
class NotificationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(NotificationBroker::class)
    fun notificationBroker(): NotificationBroker =
        InMemoryNotificationBroker()
}
