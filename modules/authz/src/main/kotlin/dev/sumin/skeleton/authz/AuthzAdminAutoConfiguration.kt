package dev.sumin.skeleton.authz

import dev.sumin.skeleton.common.authz.AuthzAutoConfiguration
import dev.sumin.skeleton.common.authz.AuthzProperties
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RoleHolders
import dev.sumin.skeleton.common.authz.RolePermissionSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.transaction.support.TransactionOperations

/**
 * 권한 `authz.roles` 선언은 모듈이 클래스패스에 있으면 늘 낸다(스위치와 무관 — 카탈로그와 yml 매핑 검증이 본다).
 * 역할 서비스는 `skeleton.authz.admin.enabled=true` 일 때만. 저장소 포트([AuthzRoleStore])는 `authz-jdbc` 가 채운다 — 켰는데 없으면 시작에 실패한다.
 * HTTP 컨트롤러는 [AuthzWebAutoConfiguration].
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
}
