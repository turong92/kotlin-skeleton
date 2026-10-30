package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.workspace.WorkspaceErrorCode
import dev.sumin.skeleton.workspace.WorkspaceException
import dev.sumin.skeleton.workspace.WorkspaceProperties
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceService
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 워크스페이스 자기 관리 - 경로에 id 가 있다(`/{id}/…`, 앱 데이터는 헤더 `X-Workspace-Id` 로 거는 [dev.sumin.skeleton.workspace.CurrentWorkspace]).
 * 호출자는 `Authentication.name`(계정 id)이다. 없는 · 남의 · 닫힌 워크스페이스는 모두 같은 `404 WORKSPACE.NOT_FOUND`.
 * `skeleton.workspace.http.enabled=false` 로 끈다.
 */
@RestController
@RequestMapping("\${skeleton.workspace.http.base-path:/api/v1/workspaces}")
class WorkspaceController(
    private val service: WorkspaceService,
    private val properties: WorkspaceProperties,
) {
    @GetMapping
    fun list(authentication: Authentication?): ListResponse<WorkspaceSummaryResponse> =
        Response.ok(service.list(accountId(authentication)).map { it.toResponse() })

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody request: CreateWorkspaceRequest): ResponseEntity<DataResponse<WorkspaceSummaryResponse>> {
        val name = request.name ?: throw invalid("name is required")
        val created = service.create(accountId(authentication), name, request.slug)
        return Response.created(URI.create("${properties.http.basePath}/${created.workspace.id}"), created.toResponse())
    }

    /** 지금 slug 든 옛 slug 든 상세를 준다 - 화면은 받은 `slug` 가 주소와 다르면 주소를 바꾼다 */
    @GetMapping("/by-slug/{slug}")
    fun bySlug(authentication: Authentication?, @PathVariable slug: String): DataResponse<WorkspaceDetailResponse> =
        Response.ok(service.bySlug(accountId(authentication), slug).toResponse())

    @GetMapping("/{id}")
    fun detail(authentication: Authentication?, @PathVariable id: String): DataResponse<WorkspaceDetailResponse> =
        Response.ok(service.detail(accountId(authentication), id).toResponse())

    @PatchMapping("/{id}")
    fun update(authentication: Authentication?, @PathVariable id: String, @RequestBody request: UpdateWorkspaceRequest): DataResponse<WorkspaceDetailResponse> =
        Response.ok(service.update(accountId(authentication), id, request.name, request.slug).toResponse())

    @GetMapping("/{id}/members")
    fun members(authentication: Authentication?, @PathVariable id: String): ListResponse<MemberResponse> =
        Response.ok(service.members(accountId(authentication), id).map { it.toResponse() })

    @PatchMapping("/{id}/members/{accountId}")
    fun setRole(authentication: Authentication?, @PathVariable id: String, @PathVariable accountId: String, @RequestBody request: RoleRequest): ResponseEntity<Void> {
        val role = WorkspaceRole.entries.firstOrNull { it.name == request.role?.trim()?.uppercase() } ?: throw invalid("role is ADMIN or MEMBER")
        service.setRole(accountId(authentication), id, accountId, role)
        return Response.noContent()
    }

    @DeleteMapping("/{id}/members/{accountId}")
    fun remove(authentication: Authentication?, @PathVariable id: String, @PathVariable accountId: String): ResponseEntity<Void> {
        service.removeMember(accountId(authentication), id, accountId)
        return Response.noContent()
    }

    @PostMapping("/{id}/leave")
    fun leave(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        service.leave(accountId(authentication), id)
        return Response.noContent()
    }

    @PostMapping("/{id}/transfer")
    fun transfer(authentication: Authentication?, @PathVariable id: String, @RequestBody request: TransferRequest): ResponseEntity<Void> {
        service.transfer(accountId(authentication), id, request.accountId ?: throw invalid("accountId is required"))
        return Response.noContent()
    }

    private fun invalid(message: String) = WorkspaceException(WorkspaceErrorCode.INVALID, message)

    private fun accountId(authentication: Authentication?): String =
        authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
