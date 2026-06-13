package dev.sumin.skeleton.api

import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.notification.websocket.NotificationWebSocketPrincipal
import dev.sumin.skeleton.notification.websocket.NotificationWebSocketTokenVerifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SkeletonNotificationWebSocketJwtConfiguration {
    @Bean
    @ConditionalOnMissingBean(NotificationWebSocketTokenVerifier::class)
    fun notificationWebSocketTokenVerifier(jwtTokenService: JwtTokenService): NotificationWebSocketTokenVerifier =
        NotificationWebSocketTokenVerifier { token ->
            jwtTokenService.authenticate(token)?.let { principal ->
                NotificationWebSocketPrincipal(
                    principalName = principal.accountId,
                    attributes = mapOf(
                        "username" to principal.username,
                        "email" to principal.email,
                        "roles" to principal.roles,
                    ),
                )
            }
        }
}
