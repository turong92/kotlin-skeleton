package dev.sumin.skeleton.account.authz

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.account.events.AccountEventType
import java.time.Instant
import dev.sumin.skeleton.common.authz.AbstractOperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.RoleHolders
import dev.sumin.skeleton.common.authz.RolePermissionSource

/**
 * 운영자 판정 — **토큰의 역할은 보지 않고** 매 요청 저장소의 계정을 읽는다(요청당 한 번 메모, 요청 사이 캐시 없음).
 * `ACTIVE` 가 아니거나 없는 계정은 빈 집합이므로 정지 · 역할 회수 · 역할 부여가 유효한 토큰을 쥔 채로 다음 호출부터 먹는다.
 */
class AccountOperatorAccess(
    private val accounts: () -> AccountRepository,
    private val source: () -> RolePermissionSource,
) : AbstractOperatorAccess() {
    override fun compute(ref: OperatorRef): Set<String> = computeRoles(ref).flatMapTo(sortedSetOf()) { source().permissionsOf(it) }

    /** 저장된 역할 — 토큰이 뭐라고 하든. `ACTIVE` 가 아니거나 없는 계정은 역할이 없다 */
    override fun computeRoles(ref: OperatorRef): Set<String> {
        val stored = accounts().findById(ref.accountId)
        if (stored == null || stored.status != AccountStatus.ACTIVE) return emptySet()
        return stored.roles.toSortedSet()
    }
}

/** 역할을 가진 활성 계정 수 — `authz` 가 「가진 계정이 있으면 역할 삭제 불가」를 판정하는 데 쓴다 */
class AccountRoleHolders(
    private val accounts: () -> AccountRepository,
    private val events: () -> AccountEventPublisher,
    private val now: () -> Instant,
) : RoleHolders {
    override fun holdersOf(role: String): Long = accounts().countActiveWithRole(role)

    /** 지운 역할의 배정을 모든 계정에서 거둔다 — 계정마다 `ROLE_REVOKED`(by = 지운 운영자) */
    override fun revokeFromAll(role: String, by: String): Int =
        accounts().revokeRoleFromAll(role, now()).onEach {
            events().publish(AccountEventType.ROLE_REVOKED, it, detail = mapOf("role" to role, "by" to by))
        }.size
}

object AccountPermissions {
    const val READ = "account.read"
    const val MANAGE = "account.manage"
    const val ROLES = "account.roles"

    val declaration = PermissionDeclaration("account", listOf(DeclaredPermission(READ), DeclaredPermission(MANAGE), DeclaredPermission(ROLES)))
}
