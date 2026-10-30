package dev.sumin.skeleton.workspace.erasure

import dev.sumin.skeleton.common.account.AccountDeletionCheck
import dev.sumin.skeleton.common.account.DeletionBlocker
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceStore

/**
 * 「유일한 소유자는 먼저 이전」(GitHub) — 다른 멤버가 있는 워크스페이스의 소유자는 계정 삭제 요청이 막힌다(`409 ACCOUNT.DELETION_BLOCKED`,
 * `refs` = 워크스페이스 id들). 혼자인 워크스페이스는 막지 않는다 - 계정이 지워질 때 함께 닫힌다.
 */
class WorkspaceDeletionCheck(private val store: WorkspaceStore) : AccountDeletionCheck {
    override fun blockers(accountId: String): List<DeletionBlocker> {
        val shared = store.ownedWorkspaceIds(accountId).filter { store.countMembers(it) > 1 }
        return if (shared.isEmpty()) emptyList() else listOf(DeletionBlocker("workspace", "OWNER", shared))
    }
}

/**
 * 그래도 계정이 지워질 때(운영자 삭제 · 유예 중 생긴 멤버)의 안전망: 소유자였던 워크스페이스는 가장 오래된 ADMIN → 없으면 가장 오래된 MEMBER 에게 넘기고,
 * 아무도 없으면 닫는다. 그 뒤 멤버 줄을 지우고 `created_by` · `invited_by` 는 묘비 값으로 바꾼다. 멱등 - 다시 불러도 더 바뀌는 것이 없다.
 */
class WorkspaceAccountErasureListener(private val store: WorkspaceStore, private val time: TimeProvider) : AccountErasureListener {
    override val name: String = "workspace"

    override fun erase(request: ErasureRequest) {
        store.ownedWorkspaceIds(request.accountId).forEach { handOver(it, request.accountId) }
        store.eraseAccount(request.accountId, request.tombstone)
    }

    private fun handOver(workspaceId: String, accountId: String) {
        store.inTransaction {
            store.lockWorkspace(workspaceId) ?: return@inTransaction
            // 잠근 뒤 다시 읽는다 - 그 사이 이전되었을 수 있다
            if (store.findMember(workspaceId, accountId)?.role != WorkspaceRole.OWNER) return@inTransaction
            val successor = store.successorCandidate(workspaceId, accountId)
            store.deleteMember(workspaceId, accountId)   // 소유자 표시를 먼저 비운다
            if (successor != null) store.setRole(workspaceId, successor.accountId, WorkspaceRole.OWNER)
            else store.closeWorkspace(workspaceId, time.now())
        }
    }
}
