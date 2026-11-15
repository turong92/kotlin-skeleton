package dev.sumin.skeleton.appcontrol.web

import dev.sumin.skeleton.appcontrol.AppControl
import dev.sumin.skeleton.appcontrol.AppControlAutoConfiguration
import dev.sumin.skeleton.common.authz.OperatorAccess
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 운영자 경로 - 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 `skeleton.app-control.admin.enabled=true` 일 때만(기본 꺼짐 → 404).
 * 인증은 앱의 보안 설정이 건다.
 */
@AutoConfiguration(after = [AppControlAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class AppControlAdminAutoConfiguration {
    @Bean
    @ConditionalOnBean(AppControl::class)
    @ConditionalOnProperty(prefix = "skeleton.app-control.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun appControlAdminController(control: AppControl, access: OperatorAccess): AppControlAdminController = AppControlAdminController(control, access)
}
