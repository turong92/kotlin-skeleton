package dev.sumin.skeleton.routing.window

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.openapi.NoContentOperation
import dev.sumin.skeleton.routing.RoutingPermissions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 로컬 창 관리 경로(`skeleton.routing.admin.enabled=true` 일 때만 — 꺼져 있으면 경로가 없어 404). 핸들러마다 `routing.window` 를 판정한다(익명 401 · 권한 없음 403) */
@RestController
@RequestMapping("/api/v1/admin/routing/local-window")
@Tag(name = "Routing local window admin")
class LocalWindowAdminController(
    private val service: LocalWindowService,
    private val access: OperatorAccess,
) {
    @Operation(summary = "Is the local route window open, and who opened it")
    @GetMapping
    fun status(authentication: Authentication?): DataResponse<LocalWindowStatusResponse> {
        operator(authentication)
        val window = service.status()
        return Response.ok(LocalWindowStatusResponse(window != null, window?.let { LocalWindowResponse.of(it) }))
    }

    @Operation(summary = "Open the local route window (closes the one that is open); minutes 1-480, a reason is required")
    @PostMapping
    fun open(authentication: Authentication?, @Valid @RequestBody request: OpenLocalWindowRequest): DataResponse<LocalWindowResponse> {
        val operator = operator(authentication)
        return Response.ok(LocalWindowResponse.of(service.open(operator.accountId, request.minutes, request.reason)))
    }

    @Operation(summary = "Close the local route window - 204 even when nothing is open")
    @NoContentOperation
    @DeleteMapping
    fun close(authentication: Authentication?): ResponseEntity<Void> {
        service.close(operator(authentication).accountId)
        return Response.noContent()
    }

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, RoutingPermissions.WINDOW) }
    }
}
