package dev.sumin.skeleton.campaign.web

import dev.sumin.skeleton.campaign.CampaignErrorCode
import dev.sumin.skeleton.campaign.Referral
import dev.sumin.skeleton.campaign.ReferralFilter
import dev.sumin.skeleton.campaign.ReferralFlag
import dev.sumin.skeleton.campaign.ReferralService
import dev.sumin.skeleton.campaign.ReferralStatus
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** `account` 모듈의 권한 이름 - 이 모듈은 `account` 를 모르니 문자열로만 안다 */
private const val ACCOUNT_READ = "account.read"

/** 운영자가 보는 초대 내역 한 줄 - 사용자 응답과 달리 표시 · 사유 · 운영자 id 까지 나간다 */
data class AdminReferralResponse(
    val inviteeId: String,
    val inviterId: String,
    val inviteeLabel: String?,
    val inviterLabel: String?,
    val campaignId: String,
    val status: ReferralStatus,
    val flags: List<ReferralFlag>,
    val qualifyEvent: String?,
    val claimedAt: Instant,
    val qualifiedAt: Instant?,
    val releaseAt: Instant?,
    val settledAt: Instant?,
    val settledBy: String?,
    val cancelReason: String?,
)

data class CancelReferralRequest(val reason: String = "")

private fun Referral.toAdmin(names: Map<String, String>) = AdminReferralResponse(
    inviteeId, inviterId, names[inviteeId], names[inviterId], campaignId, status, flags.sorted(), qualifyEvent,
    claimedAt, qualifiedAt, releaseAt, settledAt, settledBy, cancelReason,
)

/**
 * 초대 운영자 경로(`skeleton.campaign.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 모든 핸들러가 `campaign.manage` 를 매 호출 판정한다
 * (새 권한은 없다). 운영 기록: `campaign.referral.approved` · `campaign.referral.canceled`(대상은 초대받은 사람 id 만).
 */
@RestController
@RequestMapping("/api/v1/admin/referrals")
class ReferralAdminController(
    private val referrals: ReferralService,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
    /** 이름 칸 - 없으면 id 만 나간다 */
    private val labels: AccountLabels? = null,
) {
    @GetMapping
    fun list(
        authentication: Authentication?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) inviterId: String?,
        @RequestParam(required = false) inviteeId: String?,
        @RequestParam(required = false) campaignId: String?,
        @RequestParam(required = false) flag: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): CursorResponse<AdminReferralResponse> {
        val operator = operatorOf(authentication, access)
        val filter = ReferralFilter(
            inviterId = inviterId?.ifBlank { null }, inviteeId = inviteeId?.ifBlank { null }, campaignId = campaignId?.ifBlank { null },
            status = status?.ifBlank { null }?.let { raw -> ReferralStatus.entries.firstOrNull { it.name == raw.trim().uppercase() } ?: bad("Unknown status '$raw'") },
            flag = flag?.ifBlank { null }?.let { raw -> ReferralFlag.entries.firstOrNull { it.name == raw.trim().uppercase() } ?: bad("Unknown flag '$raw'") },
        )
        val page = referrals.list(filter, cursor?.ifBlank { null }, size ?: ReferralService.DEFAULT_PAGE)
        // 이메일 · 아이디는 계정을 찾고 보는 일이라 `account.read` 도 있는 운영자에게만 - 없으면 id 만 나간다
        val names = labels?.takeIf { access.has(operator, ACCOUNT_READ) }?.of(page.items.flatMap { listOf(it.inviteeId, it.inviterId) }.distinct()).orEmpty()
        return Response.ok(page.items.map { it.toAdmin(names) }, nextCursor = page.nextCursor)
    }

    @PostMapping("/{inviteeId}/approve")
    fun approve(authentication: Authentication?, @PathVariable inviteeId: String): ResponseEntity<Void> {
        val operator = operatorOf(authentication, access)
        referrals.approve(inviteeId, operator.accountId)
        audit.record(OperatorAction(operator.accountId, "campaign.referral.approved", "referral", inviteeId))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{inviteeId}/cancel")
    fun cancel(authentication: Authentication?, @PathVariable inviteeId: String, @RequestBody body: CancelReferralRequest): ResponseEntity<Void> {
        val operator = operatorOf(authentication, access)
        referrals.cancelByOperator(inviteeId, body.reason, operator.accountId)
        audit.record(OperatorAction(operator.accountId, "campaign.referral.canceled", "referral", inviteeId))
        return ResponseEntity.noContent().build()
    }

    private fun bad(message: String): Nothing = throw ApplicationException(message, PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
}
