package dev.sumin.skeleton.workspace

import java.time.Instant

/**
 * 워크스페이스 저장 포트 — `workspace-jdbc` 가 구현한다. **모든 변경은 [lockWorkspace] 로 워크스페이스 줄을 먼저 잡고** 판정한다
 * (소유자가 정확히 하나라는 규칙, 좌석 상한, 역할 검사가 모두 이 잠금 아래에서 현재 값으로 이뤄진다).
 */
interface WorkspaceStore {
    /** 열린 트랜잭션이 있으면 거기에 합류하고, 없으면 새로 연다 */
    fun <T> inTransaction(block: () -> T): T

    // 워크스페이스

    /** slug 가 지금 쓰이고 있지 않을 때만 넣는다. 넣었으면 true */
    fun insertWorkspace(workspace: Workspace): Boolean

    fun findWorkspace(id: String): Workspace?

    /** 줄을 잠그고 현재 값을 읽는다 (`for update`) */
    fun lockWorkspace(id: String): Workspace?

    fun findWorkspaceBySlug(slug: String): Workspace?

    /** 옛 slug 가 가리키는 워크스페이스 id — 이 slug 는 그 워크스페이스만 다시 가질 수 있다 */
    fun slugRetiredTo(slug: String): String?

    /** 이름 · slug 를 바꾼다. slug 가 다른 워크스페이스에 이미 쓰이면 false */
    fun updateWorkspace(id: String, name: String, slug: String, at: Instant): Boolean

    fun retireSlug(slug: String, workspaceId: String, at: Instant)

    fun releaseSlug(slug: String)

    fun closeWorkspace(id: String, at: Instant): Boolean

    // 멤버

    /** (워크스페이스, 계정) 줄이 없을 때만 넣는다. 소유자 줄이 이미 있는 워크스페이스에 소유자를 또 넣을 수 없다(DB 가 막는다) */
    fun insertMember(member: WorkspaceMember): Boolean

    fun findMember(workspaceId: String, accountId: String): WorkspaceMember?

    /** 멤버이고 워크스페이스가 `ACTIVE` 일 때만 — 경계를 거는 읽기 */
    fun activeMembership(workspaceId: String, accountId: String): WorkspaceMember?

    /** 들어온 순 */
    fun listMembers(workspaceId: String): List<WorkspaceMember>

    fun countMembers(workspaceId: String): Int

    /** 대기 중이고 아직 만료되지 않은 초대 수 */
    fun countLiveInvitations(workspaceId: String, now: Instant): Int

    /** 역할을 바꾼다. [WorkspaceRole.OWNER] 로 바꾸면 소유자 표시가 서고, 아니면 사라진다 — 소유자를 옮길 때는 옛 소유자를 먼저 내린다 */
    fun setRole(workspaceId: String, accountId: String, role: WorkspaceRole): Boolean

    fun deleteMember(workspaceId: String, accountId: String): Boolean

    /** 내가 멤버인 `ACTIVE` 워크스페이스들(들어온 순) */
    fun workspacesOf(accountId: String): List<MyWorkspace>

    /** 내가 소유자인 `ACTIVE` 워크스페이스 id */
    fun ownedWorkspaceIds(accountId: String): List<String>

    /** [excluding] 말고 가장 오래된 ADMIN, 없으면 가장 오래된 MEMBER (소유자 계정이 지워질 때의 이어받을 사람) */
    fun successorCandidate(workspaceId: String, excluding: String): WorkspaceMember?

    /** 계정이 지워질 때: 멤버 줄을 지우고 `created_by` · `invited_by` · `accepted_by` 를 [tombstone] 으로 바꾼다 (멱등). 바뀐 줄 수 */
    fun eraseAccount(accountId: String, tombstone: String): Int
}
