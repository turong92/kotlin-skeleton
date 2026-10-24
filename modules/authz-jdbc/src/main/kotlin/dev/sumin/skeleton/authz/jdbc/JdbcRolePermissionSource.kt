package dev.sumin.skeleton.authz.jdbc

import dev.sumin.skeleton.common.authz.ALL_PERMISSIONS
import dev.sumin.skeleton.common.authz.RolePermissionSource
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 표가 진실인 역할 → 권한. 전권 역할([superRole])은 표의 줄이 어떻든 `*` 이다 — 표를 잘못 만져도 운영자가 문밖으로 잠기지 않는다.
 * 캐시는 [dev.sumin.skeleton.common.authz.CachingRolePermissionSource] 가 감싼다.
 */
class JdbcRolePermissionSource(private val jdbc: JdbcClient, private val superRole: String) : RolePermissionSource {
    override fun permissionsOf(role: String): Set<String> {
        if (role == superRole) return setOf(ALL_PERMISSIONS)
        return jdbc.sql("select permission from authz_role_permissions where role = :role").param("role", role).query { rs, _ -> rs.getString("permission") }.list().toSet()
    }

    override fun definedRoles(): Set<String> =
        (jdbc.sql("select name from authz_roles").query { rs, _ -> rs.getString("name") }.list() + superRole).toSet()
}
