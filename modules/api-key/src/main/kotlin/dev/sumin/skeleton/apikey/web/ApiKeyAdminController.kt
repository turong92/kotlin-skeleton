package dev.sumin.skeleton.apikey.web

import dev.sumin.skeleton.apikey.ApiKeyOwnerTypes
import dev.sumin.skeleton.apikey.ApiKeyPermissions
import dev.sumin.skeleton.apikey.ApiKeyStatus
import dev.sumin.skeleton.apikey.ApiKeys
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `account` 모듈의 권한 이름 - 이 모듈은 권한 문자열로만 안다 */
private const val ACCOUNT_READ = "account.read"
private const val DEFAULT_SIZE = 50

/**
 * 운영자 경로(`skeleton.api-key.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 핸들러마다 권한을 [OperatorAccess] 로 판정한다(익명 401 · 권한 없음 403).
 * 키로는 닿지 않는다 - `@ApiKeyScope` 표시가 없고, 키의 주체는 역할이 비어 있다.
 */
@RestController
@RequestMapping("\${skeleton.api-key.admin.base-path:/api/v1/admin/api-keys}")
class ApiKeyAdminController(
    private val keys: ApiKeys,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
    private val time: TimeProvider,
    /** 계정 이름 - 없으면(`account` 가 없는 조립) 이름 칸이 비어 id 만 나간다 */
    private val labels: AccountLabels? = null,
) {
    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) ownerId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<AdminApiKeyResponse> {
        val operator = operator(authentication, ApiKeyPermissions.READ)
        val wanted = status?.takeIf { it.isNotBlank() }?.let { raw ->
            ApiKeyStatus.entries.firstOrNull { it.name == raw.trim().uppercase() }
                ?: throw ApplicationException("Unknown status '$raw'", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        }
        val page = keys.page(ownerId?.takeIf { it.isNotBlank() }, wanted, cursor?.takeIf { it.isNotBlank() }, size ?: DEFAULT_SIZE)
        // 이메일 · 아이디는 계정을 찾고 보는 일이라 `account.read` 도 있는 운영자에게만 - 없으면 id 만 나간다
        val names = labels?.takeIf { access.has(operator, ACCOUNT_READ) }
            ?.of(page.items.filter { it.ownerType == ApiKeyOwnerTypes.ACCOUNT }.map { it.ownerId }.distinct()) ?: emptyMap()
        val now = time.now()
        return Response.ok(page.items.map { it.toAdminResponse(now, names[it.ownerId]) }, nextCursor = page.nextCursor)
    }

    /** 사유는 키에만 남고 운영 기록 · 계정 이벤트에는 없다. 이미 회수된 키는 204 그대로(기록 없음) */
    @PostMapping("/{id}/revoke")
    fun revoke(authentication: Authentication?, @PathVariable id: String, @Valid @RequestBody request: OperatorRevokeRequest): ResponseEntity<Void> {
        val operator = operator(authentication, ApiKeyPermissions.REVOKE)
        if (keys.revokeByOperator(operator.accountId, id, request.reason!!)) {
            audit.record(OperatorAction(operator.accountId, "api-key.key.revoked", "api-key", id))
        }
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
