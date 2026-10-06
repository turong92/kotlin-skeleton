package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

/** 운영자 연산 — 호출자가 관리자인지는 HTTP 층(`skeleton.account.admin.role`)이 본다. 여기서는 사고를 막는 규칙(자기 정지 · 마지막 관리자)만 */
class AdminService(private val core: AccountCore) {
    private val adminRole get() = core.props.admin.role

    fun search(email: String?, status: AccountStatus?, page: Int, size: Int): AccountPage =
        core.accounts.search(email?.let(Emails::normalize)?.takeIf { it.isNotEmpty() }, status, page.coerceAtLeast(0), size.coerceIn(1, 100))

    fun get(id: String): Account = core.accounts.findById(id) ?: throw AccountException(AccountErrorCode.NOT_FOUND)

    fun suspend(actorId: String, targetId: String, reason: String?) {
        if (actorId == targetId) throw AccountException(AccountErrorCode.SELF_ACTION_FORBIDDEN)
        val target = get(targetId)
        if (target.status == AccountStatus.DELETED) throw AccountException(AccountErrorCode.NOT_FOUND)
        if (target.status == AccountStatus.SUSPENDED) return
        when (core.accounts.updateUnlessLast(targetId, AccountPatch(status = AccountStatus.SUSPENDED, suspendedReason = reason?.take(200)), core.time.now(), adminRole)) {
            GuardedResult.LAST -> throw AccountException(AccountErrorCode.LAST_ADMIN)
            GuardedResult.NOT_FOUND -> throw AccountException(AccountErrorCode.NOT_FOUND)
            GuardedResult.DONE -> Unit
        }
        core.sessions()?.revokeAll(targetId, null)
        core.events.publish(AccountEventType.ACCOUNT_SUSPENDED, targetId, detail = mapOf("by" to actorId))
    }

    fun unsuspend(actorId: String, targetId: String) {
        val target = get(targetId)
        if (target.status != AccountStatus.SUSPENDED) return
        core.accounts.update(targetId, AccountPatch(status = reopenedStatus(target), clearSuspendedReason = true), core.time.now())
        core.events.publish(AccountEventType.ACCOUNT_UNSUSPENDED, targetId, detail = mapOf("by" to actorId))
    }

    /** 삭제 유예 안의 계정을 되살린다. 이미 지워졌으면(행이 없다) NOT_FOUND */
    fun restore(actorId: String, targetId: String) {
        val target = get(targetId)
        if (target.status != AccountStatus.DELETED) throw AccountException(AccountErrorCode.NOT_FOUND)
        // 유예가 끝났으면 되살릴 수 없다 — 지우기와 되살리기 중 하나만 이긴다 (저장소가 한 문장으로 판정한다)
        if (!core.accounts.restore(targetId, reopenedStatus(target), core.time.now())) throw AccountException(AccountErrorCode.NOT_FOUND)
        core.events.publish(AccountEventType.ACCOUNT_RESTORED, targetId, detail = mapOf("by" to actorId))
    }

    fun grantRole(actorId: String, targetId: String, role: String) {
        validRole(role)
        if (get(targetId).status == AccountStatus.DELETED) throw AccountException(AccountErrorCode.NOT_FOUND)
        if (core.accounts.grantRole(targetId, role, core.time.now())) core.events.publish(AccountEventType.ROLE_GRANTED, targetId, detail = mapOf("role" to role, "by" to actorId))
    }

    fun revokeRole(actorId: String, targetId: String, role: String) {
        validRole(role)
        get(targetId)
        val revoked = if (role == adminRole) {
            when (core.accounts.revokeRoleUnlessLast(targetId, role, core.time.now())) {
                GuardedResult.LAST -> throw AccountException(AccountErrorCode.LAST_ADMIN)
                GuardedResult.DONE -> true
                GuardedResult.NOT_FOUND -> false
            }
        } else {
            core.accounts.revokeRole(targetId, role, core.time.now())
        }
        if (revoked) core.events.publish(AccountEventType.ROLE_REVOKED, targetId, detail = mapOf("role" to role, "by" to actorId))
    }

    private fun reopenedStatus(a: Account) = if (a.emailVerified || a.email == null) AccountStatus.ACTIVE else AccountStatus.PENDING_VERIFICATION

    private fun validRole(role: String) {
        if (!ROLE.matches(role)) throw ApplicationException("Invalid role name", PlatformErrorCode.VALIDATION_FAILED)
    }

    private companion object { val ROLE = Regex("^[A-Z][A-Z0-9_]{1,31}$") }
}
