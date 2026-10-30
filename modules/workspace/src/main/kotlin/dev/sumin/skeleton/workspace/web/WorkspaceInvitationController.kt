package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.workspace.WorkspaceErrorCode
import dev.sumin.skeleton.workspace.WorkspaceException
import dev.sumin.skeleton.workspace.WorkspaceInvitationService
import dev.sumin.skeleton.workspace.WorkspaceRole
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 워크스페이스의 초대 관리(ADMIN 이상) - `/{id}/invitations`. `skeleton.workspace.invitation.enabled=true` 이고 사용자 경로가 켜져 있을 때만 등록된다.
 * 메일 언어는 요청의 `Accept-Language` (초대하는 사람의 언어). 응답 어디에도 토큰이 없다 - 메일 본문에만.
 */
@RestController
@RequestMapping("\${skeleton.workspace.http.base-path:/api/v1/workspaces}")
@Tag(name = "Workspace")
class WorkspaceInvitationController(private val service: WorkspaceInvitationService) {
    @Operation(summary = "Pending invitations of a workspace, oldest first (admin or owner)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.LIST, value = InvitationResponse::class)
    @GetMapping("/{id}/invitations")
    fun list(authentication: Authentication?, @PathVariable id: String): ListResponse<InvitationResponse> =
        Response.ok(service.list(accountId(authentication), id).map { it.toResponse() })

    @Operation(
        summary = "Invite up to 20 email addresses (admin or owner; only the owner can invite admins)",
        description = "Each address lands in invited, alreadyInvited (a pending invitation exists - resend it), alreadyMember (the verified address already belongs to a member) or seatLimited. 409 WORKSPACE.TOO_MANY_PENDING refuses the whole request.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = InviteResponse::class)
    @PostMapping("/{id}/invitations")
    fun invite(
        authentication: Authentication?,
        @PathVariable id: String,
        @RequestBody request: InviteRequest,
        @RequestHeader("Accept-Language", required = false) language: String?,
    ): DataResponse<InviteResponse> {
        val role = WorkspaceRole.entries.firstOrNull { it.name == (request.role ?: "MEMBER").trim().uppercase() } ?: throw invalid("role is ADMIN or MEMBER")
        return Response.ok(service.invite(accountId(authentication), id, request.emails.orEmpty(), role, language).toResponse())
    }

    @Operation(summary = "Resend with a new link (the old one stops working at once); 429 WORKSPACE.RESEND_TOO_SOON within 60 seconds")
    @ApiResponseEnvelope(type = ApiEnvelopeType.BASIC)
    @PostMapping("/{id}/invitations/{invitationId}/resend")
    fun resend(
        authentication: Authentication?,
        @PathVariable id: String,
        @PathVariable invitationId: String,
        @RequestHeader("Accept-Language", required = false) language: String?,
    ): ResponseEntity<Void> {
        service.resend(accountId(authentication), id, invitationId, language)
        return Response.noContent()
    }

    @Operation(summary = "Revoke a pending invitation (frees the address and the seat)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.BASIC)
    @DeleteMapping("/{id}/invitations/{invitationId}")
    fun revoke(authentication: Authentication?, @PathVariable id: String, @PathVariable invitationId: String): ResponseEntity<Void> {
        service.revoke(accountId(authentication), id, invitationId)
        return Response.noContent()
    }

    private fun invalid(message: String) = WorkspaceException(WorkspaceErrorCode.INVALID, message)

    private fun accountId(authentication: Authentication?): String =
        authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
