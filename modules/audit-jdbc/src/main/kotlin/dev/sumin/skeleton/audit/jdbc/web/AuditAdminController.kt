package dev.sumin.skeleton.audit.jdbc.web

import dev.sumin.skeleton.audit.jdbc.AuditEntry
import dev.sumin.skeleton.audit.jdbc.AuditLogStore
import dev.sumin.skeleton.audit.jdbc.AuditPermissions
import dev.sumin.skeleton.audit.jdbc.AuditQuery
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 운영 기록 조회 — `skeleton.audit.admin.enabled=true` 일 때만(꺼져 있으면 경로가 없어 404). 읽기 전용: 고치거나 지우는 경로는 없다.
 * 권한 `audit.read`(익명 401 · 권한 없음 403). 줄에는 운영자 id · 접속 IP · 기기 정보가 들어 있다 — 꼭 필요한 운영자에게만 준다.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@Tag(name = "Operator audit log")
class AuditAdminController(private val store: AuditLogStore, private val access: OperatorAccess) {
    @Operation(
        summary = "Operator actions, newest first",
        description = "Filters: from (inclusive) / to (exclusive) as ISO-8601 instants, actorId, targetType, targetId. Page with cursor = the previous page's nextCursor. 400 for from after to or a size outside 1..100.",
    )
    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) actorId: String?,
        @RequestParam(required = false) targetType: String?,
        @RequestParam(required = false) targetId: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") size: Int,
    ): CursorResponse<AuditEntry> {
        operator(authentication)
        if (size !in 1..MAX_SIZE) throw ApplicationException("size must be between 1 and $MAX_SIZE", PlatformErrorCode.VALIDATION_FAILED)
        if (from != null && to != null && from.isAfter(to)) throw ApplicationException("from must not be after to", PlatformErrorCode.VALIDATION_FAILED)
        val lines = store.find(AuditQuery(from, to, actorId, targetType, targetId, cursor, size + 1))
        return Response.ok(lines.take(size), lines.size > size) { it.id }
    }

    private fun operator(authentication: Authentication?) {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        access.require(OperatorRef(authentication.name, roles), AuditPermissions.READ)
    }

    private companion object {
        const val MAX_SIZE = 100
    }
}
