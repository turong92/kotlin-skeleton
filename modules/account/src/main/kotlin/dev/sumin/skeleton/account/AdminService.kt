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
        guardLastAdmin(target)
        core.accounts.update(targetId, AccountPatch(status = AccountStatus.SUSPENDED, suspendedReason = reason?.take(200)), core.time.now())
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
        core.accounts.update(targetId, AccountPatch(status = reopenedStatus(target), clearDeletion = true), core.time.now())
        core.events.publish(AccountEventType.ACCOUNT_RESTORED, targetId, detail = mapOf("by" to actorId))
    }

    fun grantRole(actorId: String, targetId: String, role: String) {
        validRole(role)
        get(targetId)
        if (core.accounts.grantRole(targetId, role, core.time.now())) core.events.publish(AccountEventType.ROLE_GRANTED, targetId, detail = mapOf("role" to role, "by" to actorId))
    }

    fun revokeRole(actorId: String, targetId: String, role: String) {
        validRole(role)
        val target = get(targetId)
        if (role == adminRole && role in target.roles && target.status == AccountStatus.ACTIVE && core.accounts.countActiveWithRole(adminRole) <= 1) {
            throw AccountException(AccountErrorCode.LAST_ADMIN)
        }
        if (core.accounts.revokeRole(targetId, role, core.time.now())) core.events.publish(AccountEventType.ROLE_REVOKED, targetId, detail = mapOf("role" to role, "by" to actorId))
    }

    private fun guardLastAdmin(target: Account) {
        if (adminRole in target.roles && target.status == AccountStatus.ACTIVE && core.accounts.countActiveWithRole(adminRole) <= 1) throw AccountException(AccountErrorCode.LAST_ADMIN)
    }

    private fun reopenedStatus(a: Account) = if (a.emailVerified || a.email == null) AccountStatus.ACTIVE else AccountStatus.PENDING_VERIFICATION

    private fun validRole(role: String) {
        if (!ROLE.matches(role)) throw ApplicationException("Invalid role name", PlatformErrorCode.VALIDATION_FAILED)
    }

    private companion object { val ROLE = Regex("^[A-Z][A-Z0-9_]{1,31}$") }
}
