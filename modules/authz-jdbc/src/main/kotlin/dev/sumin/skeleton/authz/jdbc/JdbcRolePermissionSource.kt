package dev.sumin.skeleton.authz.jdbc

import dev.sumin.skeleton.common.authz.ALL_PERMISSIONS
import dev.sumin.skeleton.common.authz.RoleClosure
import dev.sumin.skeleton.common.authz.RolePermissionSource
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 표가 진실인 역할 → 권한(물려받은 것까지 펼친 값). 전권 역할([superRole])은 표의 줄이 어떻든 `*` 이다 — 표를 잘못 만져도 운영자가 문밖으로 잠기지 않는다.
 * 반대로 표를 잘못 만져도 전권이 다른 역할로 새지 않는다 — 전권 역할은 물려받는 쪽으로 따라가지 않는다.
 * 캐시는 [dev.sumin.skeleton.common.authz.CachingRolePermissionSource] 가 감싼다.
 */
class JdbcRolePermissionSource(private val jdbc: JdbcClient, private val superRole: String) : RolePermissionSource {
    override fun permissionsOf(role: String): Set<String> {
        if (role == superRole) return setOf(ALL_PERMISSIONS)
        val roles = RoleClosure.expand(setOf(role), ::includesOf)
        return jdbc.sql("select permission from authz_role_permissions where role in (:roles)").param("roles", roles)
            .query { rs, _ -> rs.getString("permission") }.list().toSortedSet()
    }

    override fun includesOf(role: String): Set<String> {
        if (role == superRole) return emptySet()
        return jdbc.sql("select included from authz_role_includes where role = :role").param("role", role)
            .query { rs, _ -> rs.getString("included") }.list().filterTo(HashSet()) { it != superRole }
    }

    override fun definedRoles(): Set<String> =
        (jdbc.sql("select name from authz_roles").query { rs, _ -> rs.getString("name") }.list() + superRole).toSet()

    /** 역할 줄을 공유 잠금으로 읽는다(`for share`: PostgreSQL · MySQL 8) — 지우는 쪽의 `for update` 와 줄 세워, 지운 역할이 계정에 남지 않게 한다 */
    override fun lockForGrant(role: String): Boolean {
        if (role == superRole) return true   // 전권 역할은 지워지지 않는다
        return jdbc.sql("select 1 from authz_roles where name = :role for share").param("role", role).query { rs, _ -> rs.getInt(1) }.list().isNotEmpty()
    }
}
