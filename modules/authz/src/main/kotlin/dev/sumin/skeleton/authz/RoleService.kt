package dev.sumin.skeleton.authz

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.authz.ALL_PERMISSIONS
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RoleHolders
import dev.sumin.skeleton.common.authz.RolePermissionSource
import org.springframework.transaction.support.TransactionOperations

data class Role(
    val name: String,
    val description: String?,
    val permissions: List<String>,
    val protected: Boolean,
    /** 역할을 가진 활성 계정 수 — [RoleHolders] 가 없으면 null */
    val holders: Long?,
)

data class PermissionInfo(val key: String, val module: String)

/**
 * 역할 관리 규칙. 호출자의 `authz.roles` 판정은 컨트롤러가 먼저 하고, 여기서는 안전장치만 건다:
 * 전권 고정 · 권한 상승 막기 · 자기 잠금 · 쓰는 계정 있는 역할 삭제 막기. 성공한 변경은 같은 트랜잭션에서 [OperatorAuditLog] 에 기록한다.
 */
class RoleService(
    private val store: AuthzRoleStore,
    private val source: RolePermissionSource,
    private val access: OperatorAccess,
    private val declarations: () -> List<PermissionDeclaration>,
    private val holders: RoleHolders?,
    private val audit: OperatorAuditLog,
    private val superRole: String,
    private val tx: TransactionOperations,
) {
    fun permissions(): List<PermissionInfo> =
        declarations().flatMap { d -> d.permissions.map { PermissionInfo(it.key, d.module) } }.sortedBy { it.key }

    fun list(): List<Role> = store.list().sortedBy { it.name }.map { it.toRole() }

    fun create(actor: OperatorRef, name: String?, description: String?, permissions: Collection<String>?): Role {
        val roleName = validName(name)
        validDescription(description)
        val wanted = validPermissions(permissions)
        return tx.execute {
            if (store.find(roleName) != null) throw exception(AuthzRoleErrorCode.ROLE_EXISTS, "Role already exists: $roleName")
            checkNoFullAccess(wanted)
            checkKnown(wanted)
            checkNoEscalation(actor, wanted)
            if (!store.insert(StoredRole(roleName, description, wanted))) {
                throw exception(AuthzRoleErrorCode.ROLE_EXISTS, "Role already exists: $roleName")
            }
            audit.record(OperatorAction(actor.accountId, "authz.role.created", "role", roleName))
            StoredRole(roleName, description, wanted).toRole()
        }
    }

    fun update(actor: OperatorRef, name: String, description: String?, permissions: Collection<String>?): Role {
        validDescription(description)
        val wanted = validPermissions(permissions)
        return tx.execute {
            val current = store.find(name) ?: throw notFound(name)
            checkNotProtected(current)
            checkNoFullAccess(wanted)
            checkKnown(wanted)
            val added = wanted - current.permissions
            val removed = current.permissions - wanted
            checkNoEscalation(actor, added)
            checkNoLockout(actor, name, wanted)
            if (!store.update(name, description, wanted)) throw notFound(name)
            audit.record(
                OperatorAction(
                    actor.accountId, "authz.role.updated", "role", name,
                    mapOf("added" to added.sorted().joinToString(","), "removed" to removed.sorted().joinToString(",")),
                ),
            )
            StoredRole(name, description, wanted).toRole()
        }
    }

    fun delete(actor: OperatorRef, name: String) {
        tx.executeWithoutResult {
            val current = store.find(name) ?: throw notFound(name)
            checkNotProtected(current)
            checkNoLockout(actor, name, emptySet())
            if ((holders?.holdersOf(name) ?: 0L) > 0) {
                throw exception(AuthzRoleErrorCode.ROLE_IN_USE, "Accounts still hold role $name")
            }
            if (!store.delete(name)) throw notFound(name)
            audit.record(OperatorAction(actor.accountId, "authz.role.deleted", "role", name))
        }
    }

    private fun StoredRole.toRole() = Role(
        name, description, permissions.sorted(), protected || name == superRole,
        holders?.holdersOf(name),
    )

    private fun checkNotProtected(role: StoredRole) {
        if (role.protected || role.name == superRole) {
            throw exception(AuthzRoleErrorCode.ROLE_PROTECTED, "Role ${role.name} is fixed and cannot be changed or deleted")
        }
    }

    private fun checkNoFullAccess(wanted: Set<String>) {
        if (ALL_PERMISSIONS in wanted) {
            throw exception(AuthzRoleErrorCode.ROLE_PROTECTED, "Only the $superRole role has full access")
        }
    }

    private fun checkKnown(wanted: Set<String>) {
        val known = declarations().flatMapTo(HashSet()) { d -> d.permissions.map { it.key } }
        val unknown = wanted.filter { it !in known }
        if (unknown.isNotEmpty()) throw exception(AuthzRoleErrorCode.PERMISSION_UNKNOWN, "Unknown permission: ${unknown.sorted().joinToString(", ")}")
    }

    /** 자기에게 없는 권한을 역할에 더할 수 없다 (Kubernetes RBAC 의 escalation 규칙). 전권은 다 가진다 */
    private fun checkNoEscalation(actor: OperatorRef, added: Set<String>) {
        val lacking = added.filter { !access.has(actor, it) }
        if (lacking.isNotEmpty()) throw exception(AuthzRoleErrorCode.ESCALATION, "You do not hold: ${lacking.sorted().joinToString(", ")}")
    }

    /**
     * 호출자가 가진 역할([OperatorRef.tokenRoles])을 고치거나 지워서 자기 `authz.roles` 를 잃으면 막는다 —
     * 다른 역할이 계속 주거나, 고친 뒤에도 남으면 괜찮다.
     */
    private fun checkNoLockout(actor: OperatorRef, name: String, after: Set<String>) {
        if (name !in actor.tokenRoles) return
        if (ALL_PERMISSIONS in after || AuthzPermissions.ROLES in after) return
        val kept = (actor.tokenRoles - name).any { role ->
            source.permissionsOf(role).let { ALL_PERMISSIONS in it || AuthzPermissions.ROLES in it }
        }
        if (!kept) throw exception(AuthzRoleErrorCode.SELF_LOCKOUT, "This change would remove your own ${AuthzPermissions.ROLES}")
    }

    private fun validName(name: String?): String {
        if (name.isNullOrEmpty()) throw FieldValidationException("name", "Required", "name is required")
        if (!NAME.matches(name)) throw FieldValidationException("name", "Pattern", "name must match ${NAME.pattern}")
        return name
    }

    private fun validDescription(description: String?) {
        if (description != null && description.length > MAX_DESCRIPTION) {
            throw FieldValidationException("description", "Size", "description must be at most $MAX_DESCRIPTION characters")
        }
    }

    private fun validPermissions(permissions: Collection<String>?): Set<String> {
        if (permissions == null) throw FieldValidationException("permissions", "Required", "permissions is required")
        return permissions.toSortedSet()
    }

    private fun notFound(name: String) = exception(AuthzRoleErrorCode.ROLE_NOT_FOUND, "Role not found: $name")

    private fun exception(code: AuthzRoleErrorCode, message: String) = AuthzRoleException(code, message)

    companion object {
        /** 계정 역할 이름과 같은 규칙 */
        val NAME = Regex("^[A-Z][A-Z0-9_]{1,31}$")
        const val MAX_DESCRIPTION = 200
    }
}
