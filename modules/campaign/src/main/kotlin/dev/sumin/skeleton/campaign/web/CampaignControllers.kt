package dev.sumin.skeleton.campaign.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.campaign.AttendanceKind
import dev.sumin.skeleton.campaign.AttendanceService
import dev.sumin.skeleton.campaign.AttendanceSummary
import dev.sumin.skeleton.campaign.Campaign
import dev.sumin.skeleton.campaign.CampaignErrorCode
import dev.sumin.skeleton.campaign.CampaignException
import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.CampaignPermissions
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.campaign.CheckInResult
import dev.sumin.skeleton.campaign.ReferralService
import dev.sumin.skeleton.campaign.ReferralSummary
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeParseException
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

/** `Authentication` → 계정 id (`Authentication.name`). 익명은 401 */
internal fun accountOf(authentication: Authentication?): String {
    if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
        throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
    }
    return authentication.name
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CampaignResponse(
    val id: String,
    val kind: String,
    val name: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val attendance: AttendanceSummaryResponse?,
)

data class MilestoneResponse(val days: Int, val streak: Boolean)

data class AttendanceSummaryResponse(val checkedToday: Boolean, val total: Int, val streak: Int, val nextMilestone: MilestoneResponse?, val today: String)

data class AttendanceMonthResponse(val days: List<String>)

private fun AttendanceSummary.toResponse() =
    AttendanceSummaryResponse(checkedToday, total, streak, nextMilestone?.let { MilestoneResponse(it.days, it.streak) }, today.toString())

/** 진행 중인 이벤트와 내 출석 현황 · 출석하기 · 이달 출석일 (`skeleton.campaign.http.enabled=false` 로 끈다) */
@RestController
@RequestMapping("/api/v1/campaigns")
class CampaignController(private val campaigns: Campaigns, private val attendance: AttendanceService) {
    @GetMapping
    fun active(authentication: Authentication?): ListResponse<CampaignResponse> {
        val account = accountOf(authentication)
        return Response.ok(
            campaigns.active().map {
                CampaignResponse(it.id, it.kind, it.name, it.startsAt, it.endsAt, if (it.kind == AttendanceKind.KIND) attendance.summary(account, it).toResponse() else null)
            },
        )
    }

    @PostMapping("/{id}/attendance")
    fun checkIn(authentication: Authentication?, @PathVariable id: String): DataResponse<CheckInResult> =
        Response.ok(attendance.checkIn(accountOf(authentication), id))

    @GetMapping("/{id}/attendance")
    fun month(authentication: Authentication?, @PathVariable id: String, @RequestParam month: String): DataResponse<AttendanceMonthResponse> {
        val parsed = try {
            YearMonth.parse(month)
        } catch (e: DateTimeParseException) {
            throw ApplicationException("month must be YYYY-MM", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
        }
        return Response.ok(AttendanceMonthResponse(attendance.month(accountOf(authentication), id, parsed).map { it.toString() }))
    }
}

data class ReferralMeResponse(val code: String, val invited: Int, val rewarded: Int)

data class ClaimRequest(val code: String = "")

/** 내 초대 코드 · 초대받은 사람이 코드를 쓰기 */
@RestController
@RequestMapping("/api/v1/referrals")
class ReferralController(private val referrals: ReferralService) {
    @GetMapping("/me")
    fun me(authentication: Authentication?): DataResponse<ReferralMeResponse> =
        referrals.me(accountOf(authentication)).let { s: ReferralSummary -> Response.ok(ReferralMeResponse(s.code, s.invited, s.rewarded)) }

    @PostMapping("/claim")
    fun claim(authentication: Authentication?, @RequestBody body: ClaimRequest): ResponseEntity<Void> {
        referrals.claim(accountOf(authentication), body.code)
        return ResponseEntity.noContent().build()
    }
}

data class AdminCampaignResponse(
    val id: String,
    val kind: String,
    val name: String,
    val enabled: Boolean,
    val startsAt: Instant,
    val endsAt: Instant?,
    val config: Map<String, Any?>,
)

data class CampaignRequest(val kind: String? = null, val name: String = "", val startsAt: Instant? = null, val endsAt: Instant? = null, val config: Map<String, Any?> = emptyMap())

data class EnabledRequest(val enabled: Boolean = false)

private fun Campaign.toAdmin() = AdminCampaignResponse(id, kind, name, enabled, startsAt, endsAt, config)

/**
 * 이벤트 운영자 경로(`skeleton.campaign.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 모든 핸들러가 `campaign.manage` 를
 * [OperatorAccess] 로 매 호출 판정한다(익명 401 · 권한 없음 403). 운영 기록: `campaign.created` · `campaign.updated`(수정 · 켜고 끄기).
 */
@RestController
@RequestMapping("\${skeleton.campaign.admin.base-path:/api/v1/admin/campaigns}")
class CampaignAdminController(
    private val campaigns: Campaigns,
    private val access: OperatorAccess,
    private val audit: OperatorAuditLog,
) {
    @GetMapping
    fun list(authentication: Authentication?): ListResponse<AdminCampaignResponse> {
        operator(authentication)
        return Response.ok(campaigns.list().map { it.toAdmin() })
    }

    @PostMapping
    fun create(authentication: Authentication?, @RequestBody body: CampaignRequest): ResponseEntity<DataResponse<AdminCampaignResponse>> {
        val operator = operator(authentication)
        val created = campaigns.create(body.kind ?: invalid("kind is required"), input(body))
        audit.record(OperatorAction(operator.accountId, "campaign.created", "campaign", created.id))
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(created.toAdmin()))
    }

    @PutMapping("/{id}")
    fun update(authentication: Authentication?, @PathVariable id: String, @RequestBody body: CampaignRequest): DataResponse<AdminCampaignResponse> {
        val operator = operator(authentication)
        val updated = campaigns.update(id, input(body))
        audit.record(OperatorAction(operator.accountId, "campaign.updated", "campaign", id))
        return Response.ok(updated.toAdmin())
    }

    @PatchMapping("/{id}/enabled")
    fun enabled(authentication: Authentication?, @PathVariable id: String, @RequestBody body: EnabledRequest): DataResponse<AdminCampaignResponse> {
        val operator = operator(authentication)
        val updated = campaigns.setEnabled(id, body.enabled)
        audit.record(OperatorAction(operator.accountId, "campaign.updated", "campaign", id, mapOf("enabled" to body.enabled.toString())))
        return Response.ok(updated.toAdmin())
    }

    private fun input(body: CampaignRequest) = CampaignInput(body.name, body.startsAt ?: invalid("startsAt is required"), body.endsAt, body.config)

    private fun invalid(message: String): Nothing = throw CampaignException(CampaignErrorCode.CONFIG_INVALID, message)

    private fun operator(authentication: Authentication?): OperatorRef {
        accountOf(authentication)
        val roles = authentication!!.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, CampaignPermissions.MANAGE) }
    }
}
