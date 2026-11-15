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
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Instant
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ModelAttribute
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
    fun list(authentication: Authentication?, @Valid @ParameterObject @ModelAttribute params: AuditLogParams): CursorResponse<AuditEntry> {
        operator(authentication)
        val lines = store.find(AuditQuery(params.from, params.to, params.actorId, params.targetType, params.targetId, params.cursor, params.size + 1))
        return Response.ok(lines.take(params.size), lines.size > params.size) { it.id }
    }

    private fun operator(authentication: Authentication?) {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        access.require(OperatorRef(authentication.name, roles), AuditPermissions.READ)
    }
}

/** 조회 조건 — `PageQuery` 와 같은 관례(Bean Validation, 어기면 `400 Validation failed` + `errors[]`). `size` 는 1~100, `from` 이 `to` 보다 늦으면 안 된다 */
data class AuditLogParams(
    val from: Instant? = null,
    val to: Instant? = null,
    val actorId: String? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val cursor: Long? = null,
    @field:Min(1)
    @field:Max(100)
    val size: Int = 50,
) {
    @get:AssertTrue(message = "from must not be after to")
    val periodOrdered: Boolean get() = from == null || to == null || !from.isAfter(to)
}
