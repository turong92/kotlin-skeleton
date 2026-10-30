package dev.sumin.skeleton.auth.mfa.web

import dev.sumin.skeleton.auth.mfa.MfaPermissions
import dev.sumin.skeleton.auth.mfa.MfaService
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val ACCOUNT_READ = "account.read"

/**
 * 운영자 해제(`skeleton.auth-mfa.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 인증 앱과 복구 코드를 모두 잃은 사람을 위한 **유일한 길**이다.
 * 권한: 상태 보기 `account.read` · 해제 `mfa.reset`(기본 역할 없음). 키로는 닿지 않는다(`@ApiKeyScope` 없음 · 키의 주체는 역할이 비어 있다).
 */
@RestController
@RequestMapping("\${skeleton.auth-mfa.admin.base-path:/api/v1/admin/mfa/accounts}")
class MfaAdminController(
    private val service: MfaService,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
) {
    @GetMapping("/{id}")
    fun status(authentication: Authentication?, @PathVariable id: String): DataResponse<MfaStatusResponse> {
        operator(authentication, ACCOUNT_READ)
        return Response.ok(service.status(id).toResponse())
    }

    @PostMapping("/{id}/reset")
    fun reset(authentication: Authentication?, @PathVariable id: String, @Valid @RequestBody request: AdminResetRequest): ResponseEntity<Void> {
        val operator = operator(authentication, MfaPermissions.RESET)
        service.reset(operator.accountId, id, access.permissionsOf(operator))
        audit.record(OperatorAction(operator.accountId, "mfa.credential.reset", "account", id))
        return Response.noContent()
    }

    private fun operator(authentication: Authentication?, permission: String): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, permission) }
    }
}
