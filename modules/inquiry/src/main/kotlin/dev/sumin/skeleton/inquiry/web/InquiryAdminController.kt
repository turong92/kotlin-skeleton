package dev.sumin.skeleton.inquiry.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.inquiry.Inquiry
import dev.sumin.skeleton.inquiry.InquiryAdminService
import dev.sumin.skeleton.inquiry.InquiryPermissions
import java.time.Instant
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 계정 id 는 싣지 않는다 - 로그인해서 보냈는지만([signedIn]). 답장은 [replyEmail](있을 때)로 운영자가 직접 보낸다 */
data class AdminInquiryResponse(
    val id: Long,
    val kind: String,
    val body: String,
    val replyEmail: String?,
    val signedIn: Boolean,
    val context: String?,
    val lang: String,
    val status: String,
    val createdAt: Instant,
    val handledAt: Instant?,
) {
    companion object {
        fun of(i: Inquiry) = AdminInquiryResponse(
            i.id, i.kind.name, i.body, i.replyEmail, i.accountId != null, i.context, i.lang, i.status.name, i.createdAt, i.handledAt,
        )
    }
}

data class InquiryStatusRequest(val status: String? = null) {
    override fun toString() = "InquiryStatusRequest(status=$status)"
}

/**
 * 운영자 경로(`skeleton.inquiry.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 핸들러마다 권한을 [OperatorAccess] 로 판정한다(익명 401 · 권한 없음 403).
 * 목록은 본문 · 이메일 원문이 든다 - `inquiry.read` 를 꼭 필요한 운영자에게만 준다.
 */
@RestController
@RequestMapping("/api/v1/admin/inquiries")
class InquiryAdminController(
    private val inquiries: InquiryAdminService,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
) {
    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): ListResponse<AdminInquiryResponse> {
        operator(authentication, InquiryPermissions.READ)
        return Response.ok(inquiries.list(status, limit).map(AdminInquiryResponse::of))
    }

    @PutMapping("/{id}/status")
    fun setStatus(authentication: Authentication?, @PathVariable id: Long, @RequestBody body: InquiryStatusRequest): DataResponse<AdminInquiryResponse> {
        val operator = operator(authentication, InquiryPermissions.HANDLE)
        val updated = inquiries.setStatus(id, body.status)
        audit.record(OperatorAction(operator.accountId, "inquiry.status.changed", "inquiry", id.toString(), mapOf("status" to updated.status.name)))
        return Response.ok(AdminInquiryResponse.of(updated))
    }

    private fun operator(authentication: Authentication?, permission: String): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, permission) }
    }
}
