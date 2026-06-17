package dev.sumin.skeleton.notification

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
class NotificationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(NotificationRecipientResolver::class)
    fun notificationRecipientResolver(): NotificationRecipientResolver =
        DefaultNotificationRecipientResolver()

    @Bean
    @ConditionalOnMissingBean(NotificationInboxRepository::class)
    fun notificationInboxRepository(): NotificationInboxRepository =
        InMemoryNotificationInboxRepository()

    @Bean
    @ConditionalOnMissingBean(NotificationBroker::class)
    fun notificationBroker(
        notificationInboxRepository: NotificationInboxRepository,
        notificationRecipientResolver: NotificationRecipientResolver,
    ): NotificationBroker =
        InMemoryNotificationBroker(notificationInboxRepository, notificationRecipientResolver)
}
