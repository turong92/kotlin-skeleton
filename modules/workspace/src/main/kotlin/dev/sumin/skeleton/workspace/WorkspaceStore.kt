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

    /** 대기 중이고 아직 만료되지 않은 초대 수 — 좌석으로 센다 */
    fun countLiveInvitations(workspaceId: String, now: Instant): Int

    // 초대 — 쓰기는 모두 [lockWorkspace] 아래에서 부른다

    /** 대기 초대를 넣는다. 같은 (워크스페이스, 주소) 의 대기 초대가 이미 있으면 DB 가 막는다(서비스가 먼저 [findPendingInvitation] 으로 거른다) */
    fun insertInvitation(invitation: WorkspaceInvitation)

    fun findInvitation(id: String): WorkspaceInvitation?

    fun findInvitationByTokenHash(tokenHash: String): WorkspaceInvitation?

    /** 이 워크스페이스에서 그 주소(소문자)로 아직 대기 중인 초대 — 만료됐어도 대기 상태면 돌려준다(재발송으로 되살린다) */
    fun findPendingInvitation(workspaceId: String, email: String): WorkspaceInvitation?

    /** 대기 상태인 초대(만료 포함), 만든 순 */
    fun listPendingInvitations(workspaceId: String): List<WorkspaceInvitation>

    /** 대기 상태인 초대 수(만료 포함) — 「대기 상한」이 센다. 좌석은 [countLiveInvitations] */
    fun countPendingInvitations(workspaceId: String): Int

    /** 새 토큰 해시 · 새 만료 · 보낸 횟수 +1 · 마지막 보낸 때. 대기 상태가 아니면 false */
    fun renewInvitation(id: String, tokenHash: String, expiresAt: Instant, at: Instant): Boolean

    /** 대기 초대를 [InvitationStatus.ACCEPTED] 또는 [InvitationStatus.REVOKED] 로 닫고 같은 주소를 다시 초대할 수 있게 비운다. 대기 상태가 아니면 false */
    fun closeInvitation(id: String, status: InvitationStatus, acceptedBy: String?, at: Instant): Boolean

    /**
     * 운영자 검색 - 닫힌 것도 포함, 새 것부터(id 내림차순). [query] 는 이름 · slug 에 대소문자 무시로 들어 있거나 id 와 같은 것(`%` · `_` 는 글자 그대로).
     * [before] 는 직전 쪽의 마지막 id. 멤버 수와 소유자를 함께 돌려준다
     */
    fun searchWorkspaces(query: String?, before: String?, limit: Int): List<WorkspaceListRow>

    /** 역할을 바꾼다. [WorkspaceRole.OWNER] 로 바꾸면 소유자 표시가 서고, 아니면 사라진다 — 소유자를 옮길 때는 옛 소유자를 먼저 내린다 */
    fun setRole(workspaceId: String, accountId: String, role: WorkspaceRole): Boolean

    fun deleteMember(workspaceId: String, accountId: String): Boolean

    /** 내가 멤버인 `ACTIVE` 워크스페이스들(들어온 순) */
    fun workspacesOf(accountId: String): List<MyWorkspace>

    /** 내가 소유자인 `ACTIVE` 워크스페이스 id */
    fun ownedWorkspaceIds(accountId: String): List<String>

    /** [excluding] 말고 가장 오래된 ADMIN, 없으면 가장 오래된 MEMBER (소유자 계정이 지워질 때의 이어받을 사람) */
    fun successorCandidate(workspaceId: String, excluding: String): WorkspaceMember?

    /** 계정이 지워질 때: 소유자가 아닌 멤버 줄을 지우고(소유자 줄은 남겨 소유자 0명이 커밋되지 않게 - 서비스가 먼저 넘긴다) `created_by` · `invited_by` · `accepted_by` 를 [tombstone] 으로 바꾼다 (멱등). 바뀐 줄 수 */
    fun eraseAccount(accountId: String, tombstone: String): Int
}
