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

    /** 지워진 계정에는 아무것도 못 한다 (읽기만) — 없는 계정(404)과 구별되는 410 으로 */
    private fun live(id: String): Account = get(id).also { if (it.status == AccountStatus.ERASED) throw AccountException(AccountErrorCode.ERASED) }

    fun suspend(actorId: String, targetId: String, reason: String?) {
        if (actorId == targetId) throw AccountException(AccountErrorCode.SELF_ACTION_FORBIDDEN)
        val target = live(targetId)   // 탈퇴 유예 중인 계정도 정지할 수 있다 — 유예가 끝나도 지워지지 않고 박제된다
        if (target.status == AccountStatus.SUSPENDED) return
        when (core.accounts.updateUnlessLast(targetId, AccountPatch(status = AccountStatus.SUSPENDED, suspendedReason = reason?.take(200)), core.time.now(), adminRole)) {
            GuardedResult.LAST -> throw AccountException(AccountErrorCode.LAST_ADMIN)
            GuardedResult.NOT_FOUND -> throw notApplied(targetId)
            GuardedResult.DONE -> Unit
        }
        core.sessions()?.revokeAll(targetId, null)
        core.events.publish(AccountEventType.ACCOUNT_SUSPENDED, targetId, detail = mapOf("by" to actorId))
    }

    fun unsuspend(actorId: String, targetId: String) {
        val target = get(targetId)
        if (target.status != AccountStatus.SUSPENDED) return
        val now = core.time.now()
        // 저장소가 거절하면(지우기를 선점했거나 그 사이 지워졌다) 풀리지 않았다 — 204 도 이벤트도 내지 않는다
        val applied = if (target.deletedAt != null) {
            // 탈퇴하려던 계정이 정지됐었다 — 정지를 풀면 탈퇴가 이어진다. 유예는 새로 (잘못된 정지였다면 그 사이 복구할 수 있게)
            val purgeAfter = maxOf(target.purgeAfter ?: now, now.plus(core.props.deletion.grace))
            core.accounts.update(targetId, AccountPatch(status = AccountStatus.DELETED, purgeAfter = purgeAfter, clearSuspendedReason = true), now)
        } else {
            core.accounts.update(targetId, AccountPatch(status = core.reopenedStatus(target), clearSuspendedReason = true), now)
        }
        if (applied == null) throw notApplied(targetId)
        core.events.publish(AccountEventType.ACCOUNT_UNSUSPENDED, targetId, detail = mapOf("by" to actorId))
    }

    /** 삭제 유예 안의 계정을 되살린다. 이미 지워졌으면(행이 없다) NOT_FOUND */
    fun restore(actorId: String, targetId: String) {
        val target = live(targetId)
        if (target.status != AccountStatus.DELETED) throw AccountException(AccountErrorCode.NOT_FOUND)
        // 유예가 끝났으면 되살릴 수 없다 — 지우기와 되살리기 중 하나만 이긴다 (저장소가 한 문장으로 판정한다)
        if (!core.accounts.restore(targetId, core.reopenedStatus(target), core.time.now())) throw AccountException(AccountErrorCode.NOT_FOUND)
        core.events.publish(AccountEventType.ACCOUNT_RESTORED, targetId, detail = mapOf("by" to actorId))
    }

    fun listBlocks(page: Int, size: Int): AccountBlockPage = core.blocks.list(page, size)

    fun removeBlock(actorId: String, blockId: Long) {
        if (!core.blocks.remove(blockId)) throw AccountException(AccountErrorCode.NOT_FOUND)
        core.events.publish(AccountEventType.REGISTRATION_BLOCK_REMOVED, null, detail = mapOf("by" to actorId, "block" to blockId.toString()))
    }

    fun grantRole(actorId: String, targetId: String, role: String) {
        validRole(role)
        if (live(targetId).status == AccountStatus.DELETED) throw AccountException(AccountErrorCode.NOT_FOUND)
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

    /** 저장소가 상태 변경을 거절했다 — 지우기가 선점했거나(409, 지우기를 다시 부르면 끝난다) 이미 지워졌거나(410) 계정이 없다(404) */
    private fun notApplied(id: String): AccountException = AccountException(
        when (core.accounts.findById(id)?.status) {
            null -> AccountErrorCode.NOT_FOUND
            AccountStatus.ERASED -> AccountErrorCode.ERASED
            else -> AccountErrorCode.ERASURE_IN_PROGRESS
        },
    )

    private fun validRole(role: String) {
        if (!ROLE.matches(role)) throw ApplicationException("Invalid role name", PlatformErrorCode.VALIDATION_FAILED)
    }

    private companion object { val ROLE = Regex("^[A-Z][A-Z0-9_]{1,31}$") }
}
