package dev.sumin.skeleton.authz.jdbc

import dev.sumin.skeleton.common.authz.ALL_PERMISSIONS
import dev.sumin.skeleton.common.authz.YmlRolePermissionSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations

/**
 * 처음 한 번만 yml 매핑([seed]: 역할별 직접 권한과 `role-includes` 위계)을 표에 옮긴다 — 표에 역할이 하나라도 있으면 아무것도 하지 않는다(그래서 운영자가 지운 `MODERATOR` 가 되살아나지 않고,
 * 기동 사이에 yml 을 바꿔도 표는 그대로다). 전권 역할은 늘 `*` · 보호로 들어간다. 두 인스턴스가 동시에 처음 뜨면 같은 줄을 insert-ignore 로 넣는다.
 */
class AuthzRoleSeeder(
    private val jdbc: JdbcClient,
    private val dialect: SqlDialect,
    private val tx: TransactionOperations,
    private val time: TimeProvider,
    private val seed: YmlRolePermissionSource,
    private val superRole: String,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        tx.executeWithoutResult {
            checkProtectedRowIsSuperRole()
            if (jdbc.sql("select count(*) from authz_roles").query(Long::class.java).single() > 0) return@executeWithoutResult
            val at = dialect.instantParam(time.now())
            val roleSql = dialect.insertIgnore("authz_roles", listOf("name", "description", "protected", "created_at", "updated_at"), listOf("name"))
            val permissionSql = dialect.insertIgnore("authz_role_permissions", listOf("role", "permission"), listOf("role", "permission"))
            val includeSql = dialect.insertIgnore("authz_role_includes", listOf("role", "included"), listOf("role", "included"))
            val roles = (seed.definedRoles() + superRole).sorted()
            roles.forEach { role ->
                val isSuper = role == superRole
                jdbc.sql(roleSql).param("name", role).param("description", if (isSuper) "Full access" else null, java.sql.Types.VARCHAR)
                    .param("protected", isSuper).param("created_at", at).param("updated_at", at).update()
                val permissions = if (isSuper) setOf(ALL_PERMISSIONS) else seed.ownPermissionsOf(role)
                permissions.forEach { jdbc.sql(permissionSql).param("role", role).param("permission", it).update() }
            }
            // 위계는 역할 줄이 다 들어간 뒤에(외래키) - 전권 역할은 물려받지도 받지도 않는다(yml 검사가 이미 막는다)
            roles.forEach { role ->
                seed.includesOf(role).sorted().forEach { jdbc.sql(includeSql).param("role", role).param("included", it).update() }
            }
        }
    }

    /** 전권 역할 이름(`skeleton.authz.super-role`)을 첫 기동 뒤 바꾸면 표의 옛 protected 줄이 이름만 바뀐 채 전권으로 남는다 — 조용히 두지 않고 멈춘다 */
    private fun checkProtectedRowIsSuperRole() {
        val stale = jdbc.sql("select name from authz_roles where protected = true and name <> :super order by name").param("super", superRole)
            .query { rs, _ -> rs.getString("name") }.list()
        check(stale.isEmpty()) {
            "authz_roles has protected role(s) ${stale.joinToString()} but skeleton.authz.super-role is '$superRole'. " +
                "The super role is fixed after the first start: set skeleton.authz.super-role back, or move the role by hand " +
                "(rename the row, then update authz_roles set protected = false where name = '<old>' once nothing relies on it)."
        }
    }
}
