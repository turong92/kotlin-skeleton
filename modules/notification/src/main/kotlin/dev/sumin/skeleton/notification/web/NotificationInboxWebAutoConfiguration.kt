package dev.sumin.skeleton.notification.web

import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationInboxRepository
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 받은편지함 HTTP 엔드포인트(`/api/v1/notifications`) — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 * 인증은 앱의 보안 설정이 건다(auth 모듈의 기본 체인은 모든 경로에 인증을 요구한다).
 */
@EnableConfigurationProperties(NotificationInboxProperties::class)
@AutoConfiguration(after = [NotificationAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.notification.inbox", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class NotificationInboxWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun notificationInboxController(inboxRepository: NotificationInboxRepository): NotificationInboxController =
        NotificationInboxController(inboxRepository)
}
