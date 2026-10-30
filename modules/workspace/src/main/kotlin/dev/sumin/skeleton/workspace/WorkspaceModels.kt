package dev.sumin.skeleton.workspace

import java.time.Instant

/** 워크스페이스 안의 역할 — 고정 셋, 큰 쪽이 작은 쪽의 권한을 모두 가진다 (`OWNER > ADMIN > MEMBER`). 앱은 「최소 역할」로 묻는다 */
enum class WorkspaceRole(private val rank: Int) {
    MEMBER(1), ADMIN(2), OWNER(3);

    fun atLeast(min: WorkspaceRole): Boolean = rank >= min.rank
}

enum class WorkspaceStatus { ACTIVE, CLOSED }

data class Workspace(
    val id: String,
    val name: String,
    /** 화면 주소의 이름 — 중복 금지, 바꿀 수 있고 옛 값은 [WorkspaceStore.slugRetiredTo] 에 남아 다른 워크스페이스가 못 가져간다 */
    val slug: String,
    val status: WorkspaceStatus,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val closedAt: Instant? = null,
)

/** 멤버십 한 줄 — 소유자는 워크스페이스마다 정확히 하나다 */
data class WorkspaceMember(
    val workspaceId: String,
    val accountId: String,
    val role: WorkspaceRole,
    val joinedAt: Instant,
    val invitedBy: String? = null,
)

/** 내 목록의 한 줄 */
data class MyWorkspace(val workspace: Workspace, val role: WorkspaceRole)

/** 상세 — [pendingInvitations] 는 아직 살아 있는(대기 · 안 만료) 초대 수, [seatLimit] 은 [WorkspaceSeatPolicy] 의 값(없으면 null = 무제한) */
data class WorkspaceDetail(
    val workspace: Workspace,
    val role: WorkspaceRole,
    val memberCount: Int,
    val pendingInvitations: Int,
    val seatLimit: Int?,
)

/** 멤버 목록의 한 줄 — [label] 은 `AccountLabels` 가 있을 때만 */
data class MemberView(val member: WorkspaceMember, val label: String?)
