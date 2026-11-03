package dev.sumin.skeleton.notification.broadcast.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.notification.broadcast.BroadcastAutoConfiguration
import dev.sumin.skeleton.notification.broadcast.Broadcasts
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 운영자 경로 - 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 `skeleton.notification.broadcast.admin.enabled=true` 일 때만(기본 꺼짐 → 404).
 * 인증은 앱의 보안 설정이 건다.
 */
@AutoConfiguration(after = [BroadcastAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class BroadcastWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(Broadcasts::class)
    @ConditionalOnProperty(prefix = "skeleton.notification.broadcast.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun broadcastAdminController(broadcasts: Broadcasts, access: OperatorAccess): BroadcastAdminController = BroadcastAdminController(broadcasts, access)
}
