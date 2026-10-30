package dev.sumin.skeleton.review.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.review.ReviewModerationService
import dev.sumin.skeleton.review.ReviewPermissions
import dev.sumin.skeleton.review.ReviewPolicy
import dev.sumin.skeleton.review.ReviewQueue
import dev.sumin.skeleton.review.ReviewReader
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
 * 리뷰 운영 경로(`skeleton.review.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 매 호출이 권한을 [OperatorAccess] 로 판정한다
 * (익명 401 · 권한 없음 403): 큐 · 숨김 · 되살리기 · 문제없음은 `review.moderate`, 답글은 `review.reply`. 앱의 [ReviewPolicy.canModerate] 가 거절할 수도 있다.
 * 운영 기록 `review.hidden` · `review.restored` · `review.reports_dismissed` · `review.replied`(대상은 리뷰 id 만).
 * 운영 처리는 모두 본문 없는 204 다.
 */
@RestController
@RequestMapping("\${skeleton.review.admin.base-path:/api/v1/admin}/reviews")
class ReviewAdminController(
    private val reader: ReviewReader,
    private val moderation: ReviewModerationService,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
    private val policy: () -> ReviewPolicy,
    /** 목록의 계정 이름 - 없으면(`account` 가 없는 조립) 이름 칸이 null 이다 */
    private val labels: AccountLabels? = null,
) {
    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(defaultValue = "reported") queue: String,
        @RequestParam(required = false) targetType: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") size: Int,
    ): CursorResponse<AdminReviewResponse> {
        val operator = operator(authentication, ReviewPermissions.MODERATE)
        if (size !in 1..MAX_SIZE) invalid("size must be 1..$MAX_SIZE")
        val which = ReviewQueue.entries.firstOrNull { it.name.equals(queue, ignoreCase = true) } ?: invalid("queue is reported, hidden or all")
        val page = reader.adminPage(which, targetType?.takeIf { it.isNotBlank() }, cursor?.takeIf { it.isNotBlank() }, size)
        // 이메일 · 아이디는 계정을 찾고 보는 일이라 `account.read` 도 있는 운영자에게만
        val names = labels?.takeIf { access.has(operator, ACCOUNT_READ) }?.of(page.rows.map { it.review.accountId }.distinct()) ?: emptyMap()
        return Response.ok(page.rows.map { it.toResponse(names[it.review.accountId]) }, page.nextCursor, page.hasNext)
    }

    @PutMapping("/{id}/moderation")
    fun moderate(authentication: Authentication?, @PathVariable id: String, @RequestBody request: ModerationRequest): ResponseEntity<Void> {
        val operator = operator(authentication, ReviewPermissions.MODERATE)
        when (request.status?.trim()?.uppercase()) {
            "HIDDEN" -> {
                moderation.hide(operator.accountId, id, request.reason)
                audit.record(OperatorAction(operator.accountId, "review.hidden", "review", id))
            }
            "PUBLISHED" -> {
                moderation.restore(id)
                audit.record(OperatorAction(operator.accountId, "review.restored", "review", id))
            }
            else -> invalid("status is PUBLISHED or HIDDEN")
        }
        return Response.noContent()
    }

    @PostMapping("/{id}/reports/dismiss")
    fun dismiss(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val operator = operator(authentication, ReviewPermissions.MODERATE)
        moderation.dismissReports(id)
        audit.record(OperatorAction(operator.accountId, "review.reports_dismissed", "review", id))
        return Response.noContent()
    }

    @PutMapping("/{id}/reply")
    fun reply(authentication: Authentication?, @PathVariable id: String, @RequestBody request: ReplyRequest): ResponseEntity<Void> {
        val operator = operator(authentication, ReviewPermissions.REPLY)
        moderation.reply(operator.accountId, id, request.body)
        audit.record(OperatorAction(operator.accountId, "review.replied", "review", id))
        return Response.noContent()
    }

    @DeleteMapping("/{id}/reply")
    fun removeReply(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val operator = operator(authentication, ReviewPermissions.REPLY)
        moderation.removeReply(id)
        audit.record(OperatorAction(operator.accountId, "review.replied", "review", id, mapOf("removed" to "true")))
        return Response.noContent()
    }

    private fun operator(authentication: Authentication?, permission: String): OperatorRef {
        val ref = OperatorRefs.require(authentication)
        access.require(ref, permission)
        if (permission == ReviewPermissions.MODERATE && !policy().canModerate(ref.accountId)) {
            throw ApplicationException("Not allowed to moderate reviews", PlatformErrorCode.FORBIDDEN)
        }
        return ref
    }

    private fun invalid(message: String): Nothing = throw ApplicationException(message, PlatformErrorCode.PARAMETER_VALIDATION_FAILED)

    private companion object {
        const val MAX_SIZE = 200
    }
}
