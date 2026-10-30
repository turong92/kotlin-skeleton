package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.workspace.WorkspaceAdminService
import dev.sumin.skeleton.workspace.WorkspacePermissions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `account` 모듈의 권한 이름 - 이 모듈은 `account` 를 모르니 문자열로만 안다 */
private const val ACCOUNT_READ = "account.read"

/**
 * 워크스페이스 운영자 경로 - 읽기만(`skeleton.workspace.admin.enabled=true` 일 때만, 꺼져 있으면 경로가 없어 404).
 * 모든 핸들러가 `workspace.read` 를 [OperatorAccess] 로 매 호출 판정한다(익명 401 · 권한 없음 403).
 * 계정 이름(소유자 · 멤버)은 계정을 찾고 보는 일이라 `account.read` 도 있는 운영자에게만 - 없으면 id 만 나간다.
 */
@RestController
@RequestMapping("\${skeleton.workspace.admin.base-path:/api/v1/admin}")
@Tag(name = "Workspace")
class WorkspaceAdminController(
    private val admin: WorkspaceAdminService,
    private val access: OperatorAccess,
    private val labels: AccountLabels? = null,
) {
    @Operation(summary = "Search workspaces, newest first (workspace.read)", description = "q matches the name or address (case-insensitive) or the exact id. Closed workspaces are included. Owner names need account.read too.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.CURSOR, value = AdminWorkspaceRow::class)
    @GetMapping("/workspaces")
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<AdminWorkspaceRow> {
        val operator = operator(authentication)
        val page = admin.search(q, cursor, size ?: DEFAULT_SIZE)
        val names = namesFor(operator, page.rows.mapNotNull { it.ownerId })
        return Response.ok(page.rows.map { it.toResponse(names) }, nextCursor = page.nextCursor)
    }

    @Operation(summary = "One workspace with its members and the number of pending invitations (workspace.read)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = AdminWorkspaceDetailResponse::class)
    @GetMapping("/workspaces/{id}")
    fun detail(authentication: Authentication?, @PathVariable id: String): DataResponse<AdminWorkspaceDetailResponse> {
        val operator = operator(authentication)
        val detail = admin.detail(id)
        val names = namesFor(operator, detail.members.map { it.accountId })
        return Response.ok(detail.toResponse(names))
    }

    private fun namesFor(operator: OperatorRef, accountIds: List<String>): Map<String, String> =
        labels?.takeIf { access.has(operator, ACCOUNT_READ) }?.of(accountIds.distinct()).orEmpty()

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, WorkspacePermissions.READ) }
    }

    private companion object {
        const val DEFAULT_SIZE = 50
    }
}
