package dev.sumin.skeleton.badge.web

import dev.sumin.skeleton.badge.BadgeAwards
import dev.sumin.skeleton.badge.BadgeDefinitionInput
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.badge.BadgePermissions
import dev.sumin.skeleton.badge.BadgeStatus
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `account` 모듈의 권한 이름 - 이 모듈은 `account` 를 모르니 문자열로만 안다 */
private const val ACCOUNT_READ = "account.read"

/**
 * 배지 운영자 경로(`skeleton.badge.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 모든 핸들러가 `badge.manage` 를
 * [OperatorAccess] 로 매 호출 판정한다(익명 401 · 권한 없음 403). 운영 기록 `badge.defined` · `badge.updated` · `badge.awarded` · `badge.revoked` (대상은 id 만).
 */
@RestController
@RequestMapping("\${skeleton.badge.admin.base-path:/api/v1/admin}")
class BadgeAdminController(
    private val definitions: BadgeDefinitions,
    private val awards: BadgeAwards,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
    /** 내역의 계정 이름 - 없으면(`account` 가 없는 조립) 이름 칸이 비어 id 만 나간다 */
    private val labels: AccountLabels? = null,
) {
    @GetMapping("/badges")
    fun list(authentication: Authentication?): ListResponse<BadgeDefinitionResponse> {
        operator(authentication)
        return Response.ok(definitions.list().map { it.toResponse() })
    }

    @PostMapping("/badges")
    fun create(authentication: Authentication?, @RequestBody request: BadgeDefinitionRequest): ResponseEntity<DataResponse<BadgeDefinitionResponse>> {
        val operator = operator(authentication)
        val created = definitions.create(request.toInput())
        audit.record(OperatorAction(operator.accountId, "badge.defined", "badge", created.id))
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(created.toResponse()))
    }

    @PutMapping("/badges/{id}")
    fun update(authentication: Authentication?, @PathVariable id: String, @RequestBody request: BadgeDefinitionRequest): DataResponse<BadgeDefinitionResponse> {
        val operator = operator(authentication)
        val updated = definitions.update(id, request.toInput())
        audit.record(OperatorAction(operator.accountId, "badge.updated", "badge", id))
        return Response.ok(updated.toResponse())
    }

    @PatchMapping("/badges/{id}/status")
    fun setStatus(authentication: Authentication?, @PathVariable id: String, @RequestBody request: StatusRequest): DataResponse<BadgeDefinitionResponse> {
        val operator = operator(authentication)
        val status = BadgeStatus.entries.firstOrNull { it.name == request.status?.trim()?.uppercase() }
            ?: throw ApplicationException("status is ACTIVE or ARCHIVED", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        val updated = definitions.setStatus(id, status)
        audit.record(OperatorAction(operator.accountId, "badge.updated", "badge", id, mapOf("status" to status.name)))
        return Response.ok(updated.toResponse())
    }

    @PostMapping("/badges/{id}/awards")
    fun award(authentication: Authentication?, @PathVariable id: String, @RequestBody request: AwardRequest): DataResponse<AwardResultResponse> {
        val operator = operator(authentication)
        val result = awards.awardByOperator(id, request.accountIds.orEmpty(), request.reason, operator.accountId)
        audit.record(OperatorAction(operator.accountId, "badge.awarded", "badge", id, mapOf("awarded" to result.awarded.size.toString(), "alreadyHeld" to result.alreadyHeld.size.toString())))
        return Response.ok(AwardResultResponse(result.awarded, result.alreadyHeld))
    }

    @PostMapping("/badge-awards/{awardId}/revoke")
    fun revoke(authentication: Authentication?, @PathVariable awardId: String, @RequestBody request: RevokeRequest): DataResponse<VisibleAwardId> {
        val operator = operator(authentication)
        awards.revokeByOperator(awardId, request.reason, operator.accountId)
        audit.record(OperatorAction(operator.accountId, "badge.revoked", "badge-award", awardId))
        return Response.ok(VisibleAwardId(awardId))
    }

    @GetMapping("/badge-awards")
    fun history(
        authentication: Authentication?,
        @RequestParam(required = false) badgeId: String?,
        @RequestParam(required = false) accountId: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<AwardEventResponse> {
        val operator = operator(authentication)
        val page = awards.history(badgeId?.takeIf { it.isNotBlank() }, accountId?.takeIf { it.isNotBlank() }, cursor, size ?: DEFAULT_SIZE)
        // 이메일 · 아이디는 계정을 찾고 보는 일이라 `account.read` 도 있는 운영자에게만 - 없으면 id 만 나간다
        val names = labels?.takeIf { access.has(operator, ACCOUNT_READ) }?.of(page.items.map { it.event.accountId }.distinct()) ?: emptyMap()
        return Response.ok(page.items.map { it.toResponse(names[it.event.accountId]) }, nextCursor = page.nextCursor)
    }

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, BadgePermissions.MANAGE) }
    }

    private fun BadgeDefinitionRequest.toInput() = BadgeDefinitionInput(code, name, description, imageUrl, hidden ?: false, sortOrder ?: 0)

    companion object {
        const val DEFAULT_SIZE = 50
    }
}

data class VisibleAwardId(val awardId: String)
