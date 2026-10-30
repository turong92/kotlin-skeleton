package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.workspace.WorkspaceInvitationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 초대 받는 쪽 - 미리보기(로그인 불필요)와 수락(로그인). 토큰은 주소가 아니라 **본문**으로 받는다 - 접근 로그에 안 남게.
 * 없는 · 만료 · 쓴 · 취소된 토큰은 모두 `400 WORKSPACE.INVITATION_INVALID` (`data.reason`: `EXPIRED` | `USED` | `INVALID`).
 */
@RestController
@RequestMapping("\${skeleton.workspace.invitation.base-path:/api/v1/workspace-invitations}")
@Tag(name = "Workspace")
class WorkspaceInvitationAcceptController(private val service: WorkspaceInvitationService) {
    @Operation(summary = "Preview an invitation from its token (public)", description = "400 WORKSPACE.INVITATION_INVALID with data.reason EXPIRED, USED or INVALID.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = InvitationPreviewResponse::class)
    @PostMapping("/preview")
    fun preview(@RequestBody request: InvitationTokenRequest): DataResponse<InvitationPreviewResponse> =
        Response.ok(service.preview(request.token.orEmpty()).toResponse())

    @Operation(
        summary = "Accept an invitation (signed in; the verified account email must equal the invited address)",
        description = "403 WORKSPACE.EMAIL_UNVERIFIED or WORKSPACE.INVITATION_EMAIL_MISMATCH; 409 WORKSPACE.ALREADY_MEMBER (data {id, slug}) or WORKSPACE.SEAT_LIMIT (the invitation stays).",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = WorkspaceSummaryResponse::class)
    @PostMapping("/accept")
    fun accept(authentication: Authentication?, @RequestBody request: InvitationTokenRequest): DataResponse<WorkspaceSummaryResponse> {
        val caller = authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        return Response.ok(service.accept(caller, request.token.orEmpty()).toResponse())
    }
}
