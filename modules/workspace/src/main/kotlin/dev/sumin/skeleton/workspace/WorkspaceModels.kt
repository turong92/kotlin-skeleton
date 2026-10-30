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

enum class InvitationStatus { PENDING, ACCEPTED, REVOKED }

/**
 * 초대 한 줄 — 토큰은 **sha256 해시만** 들고 있다(평문은 메일 본문에만). 만료는 상태가 아니라 [expiresAt] 으로 읽는다.
 * [toString] 은 주소 · 해시를 싣지 않는다(로그에 새지 않게).
 */
data class WorkspaceInvitation(
    val id: String,
    val workspaceId: String,
    /** 소문자 */
    val email: String,
    val role: WorkspaceRole,
    val tokenHash: String,
    val status: InvitationStatus,
    val invitedBy: String,
    val expiresAt: Instant,
    val sentCount: Int,
    val lastSentAt: Instant,
    val acceptedBy: String? = null,
    val acceptedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun expired(now: Instant): Boolean = !expiresAt.isAfter(now)

    override fun toString() = "WorkspaceInvitation(id=$id, workspaceId=$workspaceId, role=$role, status=$status)"
}

/** 초대 목록의 한 줄 */
data class InvitationView(val invitation: WorkspaceInvitation, val expired: Boolean)

/** 초대하기의 결과 — 주소 하나가 셋 중 하나에 들어간다 */
data class InviteResult(val invited: List<String>, val alreadyInvited: List<String>, val seatLimited: List<String>)

/** 로그인 없이 보이는 초대 미리보기 — 주소는 가려서 */
data class InvitationPreview(val workspaceName: String, val inviterLabel: String?, val emailMasked: String, val role: WorkspaceRole, val expiresAt: Instant)
