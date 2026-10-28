package dev.sumin.skeleton.reward.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.reward.RewardGrantRecord
import dev.sumin.skeleton.reward.RewardGrantService
import dev.sumin.skeleton.reward.RewardGrantStatus
import dev.sumin.skeleton.reward.RewardPermissions
import java.time.Instant
import java.util.Locale
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class RewardGrantResponse(
    val id: String,
    val accountId: String,
    val type: String,
    val label: String,
    val status: RewardGrantStatus,
    val sourceType: String,
    val lastError: String?,
    val createdAt: Instant,
    val issuedAt: Instant?,
)

/**
 * 지급 건 운영자 경로(`skeleton.reward.admin.enabled=true` 일 때만 — 꺼져 있으면 경로가 없어 404). 모든 핸들러가 `reward.operate` 를
 * [OperatorAccess] 로 매 호출 판정한다(익명 401 · 권한 없음 403).
 */
@RestController
@RequestMapping("\${skeleton.reward.admin.base-path:/api/v1/admin/reward-grants}")
class RewardAdminController(
    private val service: RewardGrantService,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
    private val issuers: () -> List<RewardIssuer>,
) {
    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<RewardGrantResponse> {
        operator(authentication)
        val wanted = status?.takeIf { it.isNotBlank() }?.let { parseStatus(it) }
        val limit = (size ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE)
        val rows = service.page(wanted, cursor?.takeIf { it.isNotBlank() }, limit + 1)
        val page = rows.take(limit)
        return Response.ok(page.map { it.toResponse() }, nextCursor = if (rows.size > limit) page.last().id else null)
    }

    @PostMapping("/{id}/retry")
    fun retry(authentication: Authentication?, @PathVariable id: String): DataResponse<RewardGrantResponse> {
        val operator = operator(authentication)
        service.retry(id) { audit.record(OperatorAction(operator.accountId, "reward.retried", "reward-grant", id)) }
        return Response.ok(service.find(id)!!.toResponse())
    }

    @PostMapping("/{id}/cancel")
    fun cancel(authentication: Authentication?, @PathVariable id: String): DataResponse<RewardGrantResponse> {
        operator(authentication)
        service.cancel(id)
        return Response.ok(service.find(id)!!.toResponse())
    }

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, RewardPermissions.OPERATE) }
    }

    private fun parseStatus(raw: String): RewardGrantStatus =
        RewardGrantStatus.entries.firstOrNull { it.name == raw.uppercase() }
            ?: throw ApplicationException("Unknown status '$raw'", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)

    private fun RewardGrantRecord.toResponse(): RewardGrantResponse {
        // 지급기를 못 찾거나 문구를 못 만들면 종류 이름을 그대로 보인다 - 목록이 문구 때문에 깨지지 않게
        val label = runCatching { issuers().firstOrNull { it.type == type }?.describe(params, Locale.getDefault())?.title }.getOrNull() ?: type
        return RewardGrantResponse(id, accountId, type, label, status, sourceType, lastError, createdAt, issuedAt)
    }

    private companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200
    }
}
