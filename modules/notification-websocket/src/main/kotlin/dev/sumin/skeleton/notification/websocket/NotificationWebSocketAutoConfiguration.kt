package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@AutoConfiguration(after = [NotificationAutoConfiguration::class])
@ConditionalOnClass(WebSocketMessageBrokerConfigurer::class, SimpMessagingTemplate::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(NotificationWebSocketProperties::class)
@ConditionalOnProperty(
    prefix = "skeleton.notification-websocket",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@Import(NotificationWebSocketBrokerConfiguration::class)
class NotificationWebSocketAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun notificationWebSocketAuthenticationInterceptor(
        properties: NotificationWebSocketProperties,
        tokenVerifier: ObjectProvider<NotificationWebSocketTokenVerifier>,
    ): NotificationWebSocketAuthenticationInterceptor =
        NotificationWebSocketAuthenticationInterceptor(
            properties = properties,
            tokenVerifier = tokenVerifier.ifAvailable,
        )

    @Bean
    @ConditionalOnMissingBean
    fun notificationWebSocketDestinationResolver(
        properties: NotificationWebSocketProperties,
    ): NotificationWebSocketDestinationResolver =
        DefaultNotificationWebSocketDestinationResolver(properties)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.notification-websocket.broker",
        name = ["bridge-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun notificationWebSocketBridge(
        subscriptionRegistry: NotificationSubscriptionRegistry,
        messagingTemplate: SimpMessagingTemplate,
        destinationResolver: NotificationWebSocketDestinationResolver,
        properties: NotificationWebSocketProperties,
    ): NotificationWebSocketBridge =
        NotificationWebSocketBridge(
            subscriptionRegistry = subscriptionRegistry,
            messagingTemplate = messagingTemplate,
            destinationResolver = destinationResolver,
            properties = properties,
        )
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class NotificationWebSocketBrokerConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["notificationWebSocketHeartbeatTaskScheduler"])
    fun notificationWebSocketHeartbeatTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            setPoolSize(1)
            setThreadNamePrefix("notification-ws-heartbeat-")
        }

    @Bean
    @ConditionalOnMissingBean(name = ["notificationWebSocketMessageBrokerConfigurer"])
    fun notificationWebSocketMessageBrokerConfigurer(
        properties: NotificationWebSocketProperties,
        authenticationInterceptor: NotificationWebSocketAuthenticationInterceptor,
        notificationWebSocketHeartbeatTaskScheduler: ThreadPoolTaskScheduler,
    ): WebSocketMessageBrokerConfigurer =
        NotificationWebSocketMessageBrokerConfigurer(
            properties = properties,
            authenticationInterceptor = authenticationInterceptor,
            heartbeatTaskScheduler = notificationWebSocketHeartbeatTaskScheduler,
        )
}
