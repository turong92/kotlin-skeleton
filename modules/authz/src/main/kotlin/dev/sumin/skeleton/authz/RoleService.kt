package dev.sumin.skeleton.authz

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.authz.ALL_PERMISSIONS
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RoleClosure
import dev.sumin.skeleton.common.authz.RoleHolders
import dev.sumin.skeleton.common.authz.RolePermissionSource
import java.util.concurrent.locks.ReentrantLock
import org.springframework.transaction.support.TransactionOperations
import kotlin.concurrent.withLock

data class Role(
    val name: String,
    val description: String?,
    val permissions: List<String>,
    val protected: Boolean,
    /** 역할을 가진 활성 계정 수 — [RoleHolders] 가 없으면 null */
    val holders: Long?,
    /** 물려받는 역할 */
    val includes: List<String> = emptyList(),
    /** 물려받은 것까지 펼친 권한 */
    val effectivePermissions: List<String> = permissions,
)

data class PermissionInfo(val key: String, val module: String)

/**
 * 역할 관리 규칙. 호출자의 `authz.roles` 판정은 컨트롤러가 먼저 하고, 여기서는 안전장치만 건다:
 * 전권 고정 · 권한 상승 막기 · 자기 잠금 · 쓰는 계정 있는 역할 삭제 막기 · 위계(`includes`)의 순환 · 없는 역할 · 전권 포함 · 물려받히는 역할 삭제 막기. 성공한 변경은 같은 트랜잭션에서 [OperatorAuditLog] 에 기록한다.
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
    /**
     * 변경 셋을 이 프로세스 안에서 한 줄로 세운다 — 행 잠금이 없는 저장소(메모리)에서도 같은 역할의 동시 고치기 · 지우기, 마지막 `authz.roles` 보호가
     * 지켜지게. **이 프로세스 안에서만** 그렇다: 잠금은 메서드가 끝나면 풀리므로 앱이 `RoleService` 를 자기 `@Transactional` 안에서 부르면 바깥 트랜잭션에 합류해
     * 커밋 전에 잠금이 풀린다 — 바깥 트랜잭션 안에서 부르지 않는다.
     *
     * 여러 인스턴스 사이는 저장소의 행 잠금(`for update`)이 **같은 역할 한 줄**의 동시 고치기 · 지우기만 직렬화한다. 「마지막 `authz.roles` 보호」(`checkNoLockout`)가 보는
     * 호출자의 **다른 역할**의 권한은 잠금 없이 읽는다(`RolePermissionSource` 의 캐시 — `cache-ttl`). 그래서 서로 다른 인스턴스에서 두 역할을 동시에 지우는 경우처럼
     * 다른 역할의 줄을 겨냥한 동시 변경은 이 보호가 서로를 보지 못해 둘 다 통과할 수 있다(전권 역할은 고정이라 완전히 잠기지는 않는다).
     */
    private val writes = ReentrantLock()

    fun permissions(): List<PermissionInfo> =
        declarations().flatMap { d -> d.permissions.map { PermissionInfo(it.key, d.module) } }.sortedBy { it.key }

    fun list(): List<Role> {
        val all = store.list().associateBy { it.name }
        return all.values.sortedBy { it.name }.map { it.toRole(all) }
    }

    /** [includes] 가 null 이면 물려받는 역할 없이 만든다 */
    fun create(actor: OperatorRef, name: String?, description: String?, permissions: Collection<String>?, includes: Collection<String>? = null): Role {
        val roleName = validName(name)
        validDescription(description)
        val wanted = validPermissions(permissions)
        val wantedIncludes = includes.orEmpty().toSortedSet()
        return writes.withLock { tx.execute {
            if (wantedIncludes.isNotEmpty()) store.lockHierarchy()
            if (store.find(roleName) != null) throw exception(AuthzRoleErrorCode.ROLE_EXISTS, "Role already exists: $roleName")
            checkNoFullAccess(wanted)
            checkKnown(wanted)
            checkNoEscalation(actor, wanted)
            val all = if (wantedIncludes.isEmpty()) emptyMap() else store.list().associateBy { it.name }
            checkIncludes(roleName, wantedIncludes, all)
            checkNoEscalation(actor, wantedIncludes.flatMapTo(HashSet()) { effectivePermissions(all, it) })
            val created = StoredRole(roleName, description, wanted, includes = wantedIncludes)
            if (!store.insert(created)) throw exception(AuthzRoleErrorCode.ROLE_EXISTS, "Role already exists: $roleName")
            audit.record(
                OperatorAction(
                    actor.accountId, "authz.role.created", "role", roleName,
                    if (wantedIncludes.isEmpty()) emptyMap() else mapOf("includes" to wantedIncludes.joinToString(",")),
                ),
            )
            created.toRole(all + (roleName to created))
        } }
    }

    /** [includes] 가 null 이면 물려받는 역할을 그대로 둔다(위계를 모르는 옛 호출이 지우지 않게) */
    fun update(actor: OperatorRef, name: String, description: String?, permissions: Collection<String>?, includes: Collection<String>? = null): Role {
        validDescription(description)
        val wanted = validPermissions(permissions)
        val wantedIncludes = includes?.toSortedSet()
        return writes.withLock { tx.execute {
            if (wantedIncludes != null) store.lockHierarchy()
            val current = store.findForUpdate(name) ?: throw notFound(name)
            checkNotProtected(current)
            checkNoFullAccess(wanted)
            checkKnown(wanted)
            val added = wanted - current.permissions
            val removed = current.permissions - wanted
            checkNoEscalation(actor, added)
            checkNoEscalation(actor, removed)   // 회수도 같은 검사 — 자기 권한 밖 권한을 빼지 못한다(account 의 회수도 상승 검사와 같다)
            val before = store.list().associateBy { it.name }
            val includesAfter = wantedIncludes ?: current.includes
            val includesAdded = includesAfter - current.includes
            val includesRemoved = current.includes - includesAfter
            if (wantedIncludes != null) {
                checkIncludes(name, includesAfter, before)
                // 위계를 더하거나 빼는 것도 그 역할의 펼친 권한을 더하거나 빼는 일이다 — 같은 상승 검사
                checkNoEscalation(actor, (includesAdded + includesRemoved).flatMapTo(HashSet()) { effectivePermissions(before, it) })
            }
            val updated = current.copy(description = description, permissions = wanted, includes = includesAfter)
            val after = before + (name to updated)
            checkNoLockout(actor, name, before, after, deleted = false)
            if (!store.update(name, description, wanted, wantedIncludes)) throw notFound(name)
            val detail = linkedMapOf("added" to added.sorted().joinToString(","), "removed" to removed.sorted().joinToString(","))
            if (includesAdded.isNotEmpty() || includesRemoved.isNotEmpty()) {
                detail["includesAdded"] = includesAdded.sorted().joinToString(",")
                detail["includesRemoved"] = includesRemoved.sorted().joinToString(",")
            }
            audit.record(OperatorAction(actor.accountId, "authz.role.updated", "role", name, detail))
            updated.toRole(after)
        } }
    }

    fun delete(actor: OperatorRef, name: String) {
        writes.withLock { tx.executeWithoutResult {
            store.lockHierarchy()   // 지우는 사이 다른 인스턴스가 이 역할을 includes 로 걸지 못하게
            val current = store.findForUpdate(name) ?: throw notFound(name)
            checkNotProtected(current)
            val includedBy = store.includedBy(name)
            if (includedBy.isNotEmpty()) {
                throw exception(AuthzRoleErrorCode.ROLE_INCLUDED, "Role $name is still included by: ${includedBy.sorted().joinToString(", ")} - remove it there first")
            }
            val before = store.list().associateBy { it.name }
            checkNoEscalation(actor, effectivePermissions(before, name))   // 자기 권한 밖 권한을 (물려받아서라도) 가진 역할은 지우지도 못한다
            checkNoLockout(actor, name, before, before - name, deleted = true)
            if ((holders?.holdersOf(name) ?: 0L) > 0) {
                throw exception(AuthzRoleErrorCode.ROLE_IN_USE, "Accounts still hold role $name")
            }
            holders?.revokeFromAll(name, actor.accountId)
            if (!store.delete(name)) throw notFound(name)
            audit.record(OperatorAction(actor.accountId, "authz.role.deleted", "role", name))
        } }
    }

    private fun StoredRole.toRole(all: Map<String, StoredRole>) = Role(
        name, description, permissions.sorted(), protected || name == superRole,
        holders?.holdersOf(name), includes.sorted(), effectivePermissions(all, name).sorted(),
    )

    /** [role] 이 물려받은 것까지 가진 권한 — 저장소의 위계 그대로(캐시를 거치지 않는다) */
    private fun effectivePermissions(all: Map<String, StoredRole>, role: String): Set<String> =
        RoleClosure.expand(setOf(role), { all[it]?.includes.orEmpty() }).flatMapTo(HashSet()) { all[it]?.permissions.orEmpty() }

    /** 물려받는 역할이 없는 역할 · 전권 역할 · 순환을 만드는 역할은 안 된다. 순서: 없는 역할 400 → 전권 409 → 순환 409 */
    private fun checkIncludes(name: String, includes: Set<String>, all: Map<String, StoredRole>) {
        if (includes.isEmpty()) return
        val unknown = includes.filter { it !in all }
        if (unknown.isNotEmpty()) throw exception(AuthzRoleErrorCode.INCLUDE_UNKNOWN, "Unknown role to include: ${unknown.sorted().joinToString(", ")}")
        if (includes.any { it == superRole || all.getValue(it).protected }) {
            throw exception(AuthzRoleErrorCode.ROLE_PROTECTED, "The full-access role $superRole cannot be included - it would hand out full access")
        }
        val graph = all.mapValues { it.value.includes } + (name to includes)
        RoleClosure.cycleOf(graph)?.let {
            throw exception(AuthzRoleErrorCode.ROLE_CYCLE, "Roles would include each other: ${it.joinToString(" -> ")}")
        }
    }

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

    /** 자기에게 없는 권한을 역할에 더하거나 빼거나 그 권한을 가진 역할을 지울 수 없다 (Kubernetes RBAC 의 escalation 규칙). 전권은 다 가진다 */
    private fun checkNoEscalation(actor: OperatorRef, added: Set<String>) {
        val lacking = added.filter { !access.has(actor, it) }
        if (lacking.isNotEmpty()) throw exception(AuthzRoleErrorCode.ESCALATION, "You do not hold: ${lacking.sorted().joinToString(", ")}")
    }

    /**
     * 호출자가 가진 역할([OperatorAccess.rolesOf] — 계정 모듈이 있으면 저장된 역할)을 고치거나 지워서 자기 `authz.roles` 를 잃으면 막는다 —
     * 다른 역할이 계속 주거나, 고친 뒤에도 남으면 괜찮다. 위계가 있어 호출자의 역할이 [name] 을 **물려받는 경우**도 이 변경에 닿는다:
     * 닿는 역할은 바뀐 위계([after])로 다시 펼치고, 안 닿는 역할은 지금 매핑([source])을 그대로 읽는다.
     */
    private fun checkNoLockout(actor: OperatorRef, name: String, before: Map<String, StoredRole>, after: Map<String, StoredRole>, deleted: Boolean) {
        val own = access.rolesOf(actor)
        fun reaches(role: String) = name in RoleClosure.expand(setOf(role), { before[it]?.includes.orEmpty() })
        if (own.none { reaches(it) }) return
        val kept = own.any { role ->
            val permissions = when {
                !reaches(role) -> source.permissionsOf(role)
                deleted && role == name -> emptySet()
                else -> effectivePermissions(after, role)
            }
            ALL_PERMISSIONS in permissions || AuthzPermissions.ROLES in permissions
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
