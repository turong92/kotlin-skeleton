package dev.sumin.skeleton.search.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.search.SearchAdmin
import dev.sumin.skeleton.search.SearchPermissions
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 검색 운영자 경로(`skeleton.search.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 모든 핸들러가 `search.manage` 를
 * [OperatorAccess] 로 매 호출 판정한다(익명 401 · 권한 없음 403). 운영 기록 `search.rebuilt` (대상은 문서 종류만).
 */
@RestController
@RequestMapping("\${skeleton.search.admin.base-path:/api/v1/admin}")
class SearchAdminController(
    private val admin: SearchAdmin,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
) {
    @GetMapping("/search/types")
    fun types(authentication: Authentication?): ListResponse<SearchTypeResponse> {
        operator(authentication)
        return Response.ok(admin.types().map { it.toResponse() })
    }

    @PostMapping("/search/rebuild")
    fun rebuild(authentication: Authentication?, @RequestBody request: RebuildRequest): ResponseEntity<DataResponse<RebuildAccepted>> {
        val operator = operator(authentication)
        val type = request.type?.trim()?.takeIf { it.isNotEmpty() } ?: throw ApplicationException("type is required", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        admin.rebuild(type)
        audit.record(OperatorAction(operator.accountId, "search.rebuilt", "search", type))
        return Response.accepted(RebuildAccepted(type))
    }

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, SearchPermissions.MANAGE) }
    }
}
