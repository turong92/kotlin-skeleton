package dev.sumin.skeleton.authz

import dev.sumin.skeleton.authz.web.AuthzAdminController
import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.AuthzProperties
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RoleHolders
import dev.sumin.skeleton.common.authz.RolePermissionSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionOperations

/**
 * 역할 관리 API(`/api/v1/admin/roles` · `/api/v1/admin/permissions`) — `skeleton.authz.admin.enabled=true` 이고 서블릿 웹 앱이며
 * Spring Security(호출자)가 클래스패스에 있을 때만. 저장소 포트([AuthzRoleStore])는 `authz-jdbc` 가 채운다 — 켰는데 없으면 시작에 실패한다.
 * 권한 `authz.roles` 선언은 모듈이 클래스패스에 있으면 늘 낸다(스위치와 무관 — 카탈로그와 yml 매핑 검증이 본다).
 */
@AutoConfiguration(after = [AuthzAutoConfiguration::class])
@EnableConfigurationProperties(AuthzAdminProperties::class)
class AuthzModuleAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["authzPermissionDeclaration"])
    fun authzPermissionDeclaration(): PermissionDeclaration = AuthzPermissions.declaration

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.authz.admin", name = ["enabled"], havingValue = "true")
    fun roleService(
        store: AuthzRoleStore,
        source: RolePermissionSource,
        access: OperatorAccess,
        declarations: ObjectProvider<PermissionDeclaration>,
        holders: ObjectProvider<RoleHolders>,
        audit: OperatorAuditLog,
        properties: AuthzProperties,
        tx: ObjectProvider<TransactionOperations>,
    ): RoleService = RoleService(
        store, source, access, { declarations.orderedStream().toList() }, holders.getIfAvailable(), audit, properties.superRole,
        tx.getIfAvailable { TransactionOperations.withoutTransaction() },
    )

    /** 컨트롤러는 Spring Security 타입을 시그니처에 쓰므로 클래스가 없는 앱에서는 이 안쪽 설정째로 건너뛴다 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "skeleton.authz.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
    class Web {
        @Bean
        @ConditionalOnMissingBean
        fun authzAdminController(service: RoleService, access: OperatorAccess): AuthzAdminController = AuthzAdminController(service, access)
    }
}
