package dev.sumin.skeleton.workspace.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.workspace.MemberView
import dev.sumin.skeleton.workspace.MyWorkspace
import dev.sumin.skeleton.workspace.WorkspaceDetail
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
