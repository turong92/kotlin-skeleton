package dev.sumin.skeleton.authz.web

import dev.sumin.skeleton.authz.AuthzPermissions
import dev.sumin.skeleton.authz.RoleService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.openapi.CreatedOperation
import dev.sumin.skeleton.common.openapi.NoContentOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/** 역할 관리 · 권한 카탈로그. `skeleton.authz.admin.enabled=true` 일 때만 등록된다 (꺼져 있으면 404) */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Authz")
class AuthzAdminController(private val service: RoleService, private val access: OperatorAccess) {
    @Operation(summary = "Permissions declared by the modules on the classpath (authz.roles)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.LIST, value = PermissionResponse::class)
    @GetMapping("/permissions")
    fun permissions(authentication: Authentication?): ListResponse<PermissionResponse> {
        access.require(operator(authentication), AuthzPermissions.ROLES)
        return Response.ok(service.permissions().map { it.toResponse() })
    }

    @Operation(summary = "List roles with their permissions (authz.roles or account.roles)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.LIST, value = RoleResponse::class)
    @GetMapping("/roles")
    fun list(authentication: Authentication?): ListResponse<RoleResponse> {
        val ref = operator(authentication)
        if (ref == null || !access.has(ref, AuthzPermissions.ROLES)) {
            // 익명이면 401, authz.roles 가 없으면 account.roles 를 한 번 더 본다 — 둘 다 없으면 403
            if (ref == null || !access.has(ref, AuthzPermissions.ACCOUNT_ROLES)) access.require(ref, AuthzPermissions.ROLES)
        }
        return Response.ok(service.list().map { it.toResponse() })
    }

    @Operation(summary = "Create a role (authz.roles); you can only grant permissions you hold")
    @CreatedOperation
    @PostMapping("/roles")
    fun create(authentication: Authentication?, @Valid @RequestBody request: CreateRoleRequest): ResponseEntity<DataResponse<RoleResponse>> {
        val ref = required(authentication)
        val role = service.create(ref, request.name, request.description, request.permissions, request.includes).toResponse()
        return Response.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{name}").buildAndExpand(role.name).toUri(), role)
    }

    @Operation(summary = "Replace a role's description, permissions and included roles (authz.roles); omitted includes stay")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = RoleResponse::class)
    @PutMapping("/roles/{name}")
    fun update(authentication: Authentication?, @PathVariable name: String, @Valid @RequestBody request: UpdateRoleRequest): DataResponse<RoleResponse> =
        Response.ok(service.update(required(authentication), name, request.description, request.permissions, request.includes).toResponse())

    @Operation(summary = "Delete a role nobody holds (authz.roles)")
    @NoContentOperation
    @DeleteMapping("/roles/{name}")
    fun delete(authentication: Authentication?, @PathVariable name: String): ResponseEntity<Void> {
        service.delete(required(authentication), name)
        return Response.noContent()
    }

    private fun required(authentication: Authentication?): OperatorRef =
        operator(authentication).also { access.require(it, AuthzPermissions.ROLES) }!!

    /** `Authentication` → [OperatorRef]. 호출자 id 는 `Authentication.name`, 토큰 역할은 `ROLE_` 를 뗀 권한 이름 */
    private fun operator(authentication: Authentication?): OperatorRef? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        return OperatorRef(authentication.name, authentication.authorities.mapTo(HashSet()) { it.authority.orEmpty().removePrefix("ROLE_") })
    }
}
