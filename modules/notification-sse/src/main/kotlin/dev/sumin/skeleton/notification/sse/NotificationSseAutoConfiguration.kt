package dev.sumin.skeleton.notification.sse

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [NotificationAutoConfiguration::class])
@EnableConfigurationProperties(NotificationSseProperties::class)
@ConditionalOnProperty(
    prefix = "skeleton.notification.sse",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class NotificationSseAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun notificationSseService(
        subscriptionRegistry: NotificationSubscriptionRegistry,
        properties: NotificationSseProperties,
    ): NotificationSseService =
        NotificationSseService(subscriptionRegistry, properties)

    @Bean
    @ConditionalOnMissingBean
    fun notificationSseController(service: NotificationSseService): NotificationSseController =
        NotificationSseController(service)

    @Bean("notificationSsePublicEndpointContributor")
    @ConditionalOnMissingBean(name = ["notificationSsePublicEndpointContributor"])
    @ConditionalOnProperty(
        prefix = "skeleton.notification.sse",
        name = ["public-endpoint"],
        havingValue = "true",
    )
    fun notificationSsePublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("GET", "/api/v1/notifications/sse")
        }
}
