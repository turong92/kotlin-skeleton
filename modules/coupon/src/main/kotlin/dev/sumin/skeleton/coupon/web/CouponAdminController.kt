package dev.sumin.skeleton.coupon.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.coupon.CouponCodes
import dev.sumin.skeleton.coupon.CouponDefinitionInput
import dev.sumin.skeleton.coupon.CouponDefinitionStatus
import dev.sumin.skeleton.coupon.CouponDefinitions
import dev.sumin.skeleton.coupon.CouponIssuance
import dev.sumin.skeleton.coupon.CouponPermissions
import dev.sumin.skeleton.coupon.CouponReports
import dev.sumin.skeleton.coupon.CouponScopeInput
import dev.sumin.skeleton.coupon.CouponStageInput
import dev.sumin.skeleton.coupon.CouponStatus
import dev.sumin.skeleton.coupon.CouponWallet
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `account` 모듈의 권한 이름 - 이 모듈은 `account` 를 모르니 문자열로만 안다 */
private const val ACCOUNT_READ = "account.read"

data class StageRequest(val type: String? = null, val value: Long? = null, val maxDiscount: Long? = null, val periods: Int? = null)

data class ScopeRequest(
    val purposes: List<String>? = null,
    val itemTypes: List<String>? = null,
    val itemRefs: List<String>? = null,
    val planCodes: List<String>? = null,
)

data class CouponDefinitionRequest(
    val name: String? = null,
    val stages: List<StageRequest>? = null,
    val currency: String? = null,
    val minAmount: Long? = null,
    val scope: ScopeRequest? = null,
    val firstPurchaseOnly: Boolean? = null,
    val validDays: Int? = null,
    val validUntil: Instant? = null,
    val issueLimit: Int? = null,
    val perAccountLimit: Int? = null,
    val issueStartsAt: Instant? = null,
    val issueEndsAt: Instant? = null,
    val validFrom: Instant? = null,
)

data class StatusRequest(val status: String? = null)

data class IssueRequest(val accountIds: List<String>? = null)

data class CodeRequest(val code: String? = null, val maxRedemptions: Int? = null, val expiresAt: Instant? = null)

/**
 * 쿠폰 운영자 경로(`skeleton.coupon.admin.enabled=true` 일 때만 — 꺼져 있으면 경로가 없어 404). 모든 핸들러가 `coupon.manage` 를
 * [OperatorAccess] 로 매 호출 판정한다(익명 401 · 권한 없음 403).
 */
@RestController
@RequestMapping("\${skeleton.coupon.admin.base-path:/api/v1/admin}")
class CouponAdminController(
    private val definitions: CouponDefinitions,
    private val issuance: CouponIssuance,
    private val codes: CouponCodes,
    private val wallet: CouponWallet,
    private val reports: CouponReports,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
    /** 발급 내역의 계정 이름 - 없으면(`account` 가 없는 조립) 이름 칸이 비어 id 만 나간다 */
    private val labels: AccountLabels? = null,
) {
    @GetMapping("/coupon-definitions")
    fun listDefinitions(authentication: Authentication?): ListResponse<CouponDefinitionResponse> {
        operator(authentication)
        return Response.ok(definitions.list().map { it.toResponse() })
    }

    @PostMapping("/coupon-definitions")
    fun createDefinition(authentication: Authentication?, @RequestBody request: CouponDefinitionRequest): ResponseEntity<DataResponse<CouponDefinitionResponse>> {
        operator(authentication)
        val created = definitions.create(request.toInput())
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(created.toResponse()))
    }

    @PatchMapping("/coupon-definitions/{id}/status")
    fun setStatus(authentication: Authentication?, @PathVariable id: String, @RequestBody request: StatusRequest): DataResponse<CouponDefinitionResponse> {
        operator(authentication)
        val status = CouponDefinitionStatus.entries.firstOrNull { it.name == request.status?.trim()?.uppercase() }
            ?: throw ApplicationException("status is ACTIVE, PAUSED or ARCHIVED", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        return Response.ok(definitions.setStatus(id, status).toResponse())
    }

    @PostMapping("/coupon-definitions/{id}/issue")
    fun issue(authentication: Authentication?, @PathVariable id: String, @RequestBody request: IssueRequest): DataResponse<IssueResponse> {
        val operator = operator(authentication)
        val accountIds = request.accountIds?.takeIf { it.isNotEmpty() }
            ?: throw ApplicationException("accountIds is required", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        val result = issuance.issueByAdmin(id, accountIds) { done ->
            audit.record(OperatorAction(operator.accountId, "coupon.issued", "coupon-definition", id, mapOf("issued" to done.issued.size.toString())))
        }
        return Response.ok(IssueResponse(result.issued, result.skipped))
    }

    @PostMapping("/coupon-definitions/{id}/codes")
    fun createCode(authentication: Authentication?, @PathVariable id: String, @RequestBody(required = false) request: CodeRequest?): ResponseEntity<DataResponse<CouponCodeResponse>> {
        operator(authentication)
        val code = codes.create(id, request?.code, request?.maxRedemptions, request?.expiresAt)
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(CouponCodeResponse(code)))
    }

    @GetMapping("/coupon-definitions/{id}/stats")
    fun stats(authentication: Authentication?, @PathVariable id: String): DataResponse<CouponStatsResponse> {
        operator(authentication)
        return Response.ok(reports.stats(id).toResponse())
    }

    @GetMapping("/coupon-definitions/{id}/coupons")
    fun issuedCoupons(
        authentication: Authentication?,
        @PathVariable id: String,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<IssuedCouponResponse> {
        val operator = operator(authentication)
        val wanted = status?.takeIf { it.isNotBlank() }?.let { raw ->
            CouponStatus.entries.firstOrNull { it.name == raw.trim().uppercase() }
                ?: throw ApplicationException("Unknown status '$raw'", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        }
        val page = reports.coupons(id, wanted, cursor?.takeIf { it.isNotBlank() }, size ?: CouponReports.DEFAULT_SIZE)
        // 이메일 · 아이디는 계정을 찾고 보는 일이라 `account.read` 도 있는 운영자에게만 - 없으면 id 만 나간다
        val names = labels?.takeIf { access.has(operator, ACCOUNT_READ) }?.of(page.items.map { it.coupon.accountId }) ?: emptyMap()
        return Response.ok(page.items.map { it.toIssuedResponse(names[it.coupon.accountId]) }, nextCursor = page.nextCursor)
    }

    @GetMapping("/coupons")
    fun listCoupons(authentication: Authentication?, @RequestParam accountId: String): ListResponse<AdminCouponResponse> {
        operator(authentication)
        return Response.ok(wallet.all(accountId).map { it.toAdminResponse() })
    }

    @PostMapping("/coupons/{id}/revoke")
    fun revoke(authentication: Authentication?, @PathVariable id: String): DataResponse<AdminCouponResponse> {
        operator(authentication)
        return Response.ok(wallet.revoke(id).toAdminResponse())
    }

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, CouponPermissions.MANAGE) }
    }

    private fun CouponDefinitionRequest.toInput() = CouponDefinitionInput(
        name = name,
        stages = (stages ?: emptyList()).map { CouponStageInput(it.type, it.value, it.maxDiscount, it.periods) },
        currency = currency,
        minAmount = minAmount,
        scope = CouponScopeInput(scope?.purposes.orEmpty(), scope?.itemTypes.orEmpty(), scope?.itemRefs.orEmpty(), scope?.planCodes.orEmpty()),
        firstPurchaseOnly = firstPurchaseOnly ?: false,
        validDays = validDays,
        validUntil = validUntil,
        issueLimit = issueLimit,
        perAccountLimit = perAccountLimit ?: 1,
        issueStartsAt = issueStartsAt,
        issueEndsAt = issueEndsAt,
        validFrom = validFrom,
    )
}
