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
}
