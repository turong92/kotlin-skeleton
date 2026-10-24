package dev.sumin.skeleton.authz

import dev.sumin.skeleton.authz.web.AuthzAdminController
import dev.sumin.skeleton.common.authz.OperatorAccess
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 역할 관리 API(`/api/v1/admin/roles` · `/api/v1/admin/permissions`) — `skeleton.authz.admin.enabled=true` 이고 서블릿 웹 앱이며
 * Spring Security(호출자)가 클래스패스에 있을 때만 (없으면 컨트롤러 클래스를 읽지도 않는다 — noOptionalTest 가 증명).
 */
@AutoConfiguration(after = [AuthzModuleAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.authz.admin", name = ["enabled"], havingValue = "true")
class AuthzWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun authzAdminController(service: RoleService, access: OperatorAccess): AuthzAdminController = AuthzAdminController(service, access)
}
