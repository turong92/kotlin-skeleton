package dev.sumin.skeleton.authz.jdbc

import dev.sumin.skeleton.common.authz.ALL_PERMISSIONS
import dev.sumin.skeleton.common.authz.RolePermissionSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations

/**
 * 처음 한 번만 yml 매핑([seed])을 표에 옮긴다 — 표에 역할이 하나라도 있으면 아무것도 하지 않는다(그래서 운영자가 지운 `MODERATOR` 가 되살아나지 않고,
 * 기동 사이에 yml 을 바꿔도 표는 그대로다). 전권 역할은 늘 `*` · 보호로 들어간다. 두 인스턴스가 동시에 처음 뜨면 같은 줄을 insert-ignore 로 넣는다.
 */
class AuthzRoleSeeder(
    private val jdbc: JdbcClient,
    private val dialect: SqlDialect,
    private val tx: TransactionOperations,
    private val time: TimeProvider,
    private val seed: RolePermissionSource,
    private val superRole: String,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        tx.executeWithoutResult {
            if (jdbc.sql("select count(*) from authz_roles").query(Long::class.java).single() > 0) return@executeWithoutResult
            val at = dialect.instantParam(time.now())
            val roleSql = dialect.insertIgnore("authz_roles", listOf("name", "description", "protected", "created_at", "updated_at"), listOf("name"))
            val permissionSql = dialect.insertIgnore("authz_role_permissions", listOf("role", "permission"), listOf("role", "permission"))
            (seed.definedRoles() + superRole).sorted().forEach { role ->
                val isSuper = role == superRole
                jdbc.sql(roleSql).param("name", role).param("description", if (isSuper) "Full access" else null, java.sql.Types.VARCHAR)
                    .param("protected", isSuper).param("created_at", at).param("updated_at", at).update()
                val permissions = if (isSuper) setOf(ALL_PERMISSIONS) else seed.permissionsOf(role)
                permissions.forEach { jdbc.sql(permissionSql).param("role", role).param("permission", it).update() }
            }
        }
    }
}
