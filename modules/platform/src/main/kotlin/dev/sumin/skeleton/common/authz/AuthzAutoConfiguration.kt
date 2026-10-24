package dev.sumin.skeleton.common.authz

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@AutoConfiguration
@EnableConfigurationProperties(AuthzProperties::class)
class AuthzAutoConfiguration {
    @Bean
    fun legacyRoleKeysGuard(environment: Environment): LegacyRoleKeysGuard = LegacyRoleKeysGuard(environment)

    @Bean
    @ConditionalOnMissingBean(RolePermissionSource::class)
    fun rolePermissionSource(properties: AuthzProperties, declarations: ObjectProvider<PermissionDeclaration>): RolePermissionSource {
        check(ROLE_NAME.matches(properties.superRole)) { "Invalid skeleton.authz.super-role '${properties.superRole}': not a role name ($ROLE_NAME)" }
        val declared = declarations.orderedStream().toList().flatMap { it.permissions }
        val roles: Map<String, Set<String>> =
            if (properties.roles.isEmpty()) defaultRoles(properties.superRole, declared)
            else explicitRoles(properties, declared.mapTo(HashSet()) { it.key })
        return YmlRolePermissionSource(roles)
    }

    @Bean
    @ConditionalOnMissingBean
    fun operatorAccess(source: RolePermissionSource): OperatorAccess = TokenOperatorAccess(source)

    @Bean
    @ConditionalOnMissingBean
    fun operatorAuditLog(): OperatorAuditLog = NoOpOperatorAuditLog()

    private fun defaultRoles(superRole: String, declared: List<DeclaredPermission>): Map<String, Set<String>> {
        val roles = linkedMapOf<String, MutableSet<String>>(superRole to sortedSetOf(ALL_PERMISSIONS))
        declared.forEach { permission ->
            permission.defaultRoles.forEach { roles.getOrPut(it) { sortedSetOf() }.add(permission.key) }
        }
        return roles
    }

    private fun explicitRoles(properties: AuthzProperties, declaredKeys: Set<String>): Map<String, Set<String>> {
        val problems = mutableListOf<String>()
        properties.roles.forEach { (role, keys) ->
            // 환경변수로 묶은 맵 키는 소문자로 들어온다 - 조용히 권한 없는 역할이 되지 않게 막는다
            if (!ROLE_NAME.matches(role)) problems += "$role: not a role name ($ROLE_NAME) - an env var binds the key in lower case, use the yml form"
            keys.forEach { key ->
                when {
                    key == ALL_PERMISSIONS ->
                        if (role != properties.superRole) problems += "$role: '*' belongs to the super role (${properties.superRole}) only"
                    !PermissionKeys.isValid(key) -> problems += "$role: '$key' is not a permission key (<module>.<verb>, lower-case, up to ${PermissionKeys.MAX_LENGTH} chars)"
                    key !in declaredKeys -> problems += "$role: '$key' is not declared by any module on the classpath"
                }
            }
        }
        check(problems.isEmpty()) { "Invalid skeleton.authz.roles:\n  " + problems.joinToString("\n  ") }
        return properties.roles.mapValues { it.value.toSortedSet() }
    }

    private companion object {
        /** 계정 역할 이름 규칙과 같다(account `AdminService`) */
        val ROLE_NAME = Regex("^[A-Z][A-Z0-9_]{1,31}$")
    }
}
