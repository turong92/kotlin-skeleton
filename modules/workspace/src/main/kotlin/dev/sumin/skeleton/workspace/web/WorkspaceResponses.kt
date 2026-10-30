package dev.sumin.skeleton.workspace.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.workspace.AdminWorkspaceDetail
import dev.sumin.skeleton.workspace.InvitationPreview
import dev.sumin.skeleton.workspace.InvitationView
import dev.sumin.skeleton.workspace.InviteResult
import dev.sumin.skeleton.workspace.MemberView
import dev.sumin.skeleton.workspace.MyWorkspace
import dev.sumin.skeleton.workspace.WorkspaceDetail
import dev.sumin.skeleton.workspace.WorkspaceListRow
import java.time.Instant

/** 내 목록의 한 줄 · 만들기의 응답 */
data class WorkspaceSummaryResponse(val id: String, val name: String, val slug: String, val role: String)

/** 상세 - `seatLimit` 은 상한이 없으면 `null` (키는 늘 나간다) */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class WorkspaceDetailResponse(
    val id: String,
    val name: String,
    val slug: String,
    val role: String,
    val memberCount: Int,
    val pendingInvitations: Int,
    val seatLimit: Int?,
    val createdAt: Instant,
)

/** 멤버 한 줄 - `label` 은 계정 이름을 알 때만 (아니면 `null`) */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class MemberResponse(val accountId: String, val label: String?, val role: String, val joinedAt: Instant)

data class CreateWorkspaceRequest(val name: String?, val slug: String?)

data class UpdateWorkspaceRequest(val name: String?, val slug: String?)

data class RoleRequest(val role: String?)

data class TransferRequest(val accountId: String?)

fun MyWorkspace.toResponse() = WorkspaceSummaryResponse(workspace.id, workspace.name, workspace.slug, role.name)

fun WorkspaceDetail.toResponse() = WorkspaceDetailResponse(workspace.id, workspace.name, workspace.slug, role.name, memberCount, pendingInvitations, seatLimit, workspace.createdAt)

fun MemberView.toResponse() = MemberResponse(member.accountId, label, member.role.name, member.joinedAt)

// 초대

data class InviteRequest(val emails: List<String>?, val role: String?)

data class InvitationTokenRequest(val token: String?) {
    /** 본문 로깅(MVC DEBUG)에 평문 토큰이 남지 않게 */
    override fun toString() = "InvitationTokenRequest(token=<redacted>)"
}

data class InviteResponse(val invited: List<String>, val alreadyInvited: List<String>, val seatLimited: List<String>, val alreadyMember: List<String>)

/** 대기 목록의 한 줄 - 토큰은 없다. `expired` 면 재발송으로 되살린다. `invitedBy` 는 초대한 계정 id */
data class InvitationResponse(val id: String, val email: String, val role: String, val expiresAt: Instant, val expired: Boolean, val sentCount: Int, val invitedBy: String)

/** 로그인 없이 보이는 미리보기 - `inviterLabel` 은 이름을 알 때만(아니면 `null`), 주소는 가려서 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class InvitationPreviewResponse(val workspaceName: String, val inviterLabel: String?, val emailMasked: String, val role: String, val expiresAt: Instant)

fun InviteResult.toResponse() = InviteResponse(invited, alreadyInvited, seatLimited, alreadyMember)

fun InvitationView.toResponse() = InvitationResponse(invitation.id, invitation.email, invitation.role.name, invitation.expiresAt, expired, invitation.sentCount, invitation.invitedBy)

fun InvitationPreview.toResponse() = InvitationPreviewResponse(workspaceName, inviterLabel, emailMasked, role.name, expiresAt)

// 운영자 (읽기)

/** 운영자 목록의 한 줄 - `ownerLabel` 은 `account.read` 가 있고 이름을 알 때만(아니면 `null`), `ownerId` 는 소유자 줄이 없으면 `null` */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AdminWorkspaceRow(val id: String, val name: String, val slug: String, val status: String, val ownerId: String?, val ownerLabel: String?, val memberCount: Int, val createdAt: Instant)

/** 운영자 상세 - 멤버의 `label` 도 `account.read` 가 있을 때만 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AdminWorkspaceDetailResponse(
    val id: String,
    val name: String,
    val slug: String,
    val status: String,
    val createdAt: Instant,
    val closedAt: Instant?,
    val ownerId: String?,
    val ownerLabel: String?,
    val memberCount: Int,
    val pendingInvitations: Int,
    val seatLimit: Int?,
    val members: List<MemberResponse>,
)

fun WorkspaceListRow.toResponse(names: Map<String, String>) =
    AdminWorkspaceRow(workspace.id, workspace.name, workspace.slug, workspace.status.name, ownerId, ownerId?.let(names::get), memberCount, workspace.createdAt)

fun AdminWorkspaceDetail.toResponse(names: Map<String, String>) = AdminWorkspaceDetailResponse(
    workspace.id, workspace.name, workspace.slug, workspace.status.name, workspace.createdAt, workspace.closedAt, ownerId, ownerId?.let(names::get), memberCount, pendingInvitations, seatLimit,
    members.map { MemberResponse(it.accountId, names[it.accountId], it.role.name, it.joinedAt) },
)
