package dev.sumin.skeleton.dashboard.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.dashboard.DashboardAssembler
import dev.sumin.skeleton.dashboard.DashboardPermissions
import dev.sumin.skeleton.dashboard.DashboardRangeParser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 관리자 대시보드 (`dashboard.read`). `skeleton.dashboard.admin.enabled=true` 일 때만 등록된다 (꺼져 있으면 404) */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@Tag(name = "Dashboard")
class DashboardAdminController(
    private val assembler: DashboardAssembler,
    private val parser: DashboardRangeParser,
    private val access: OperatorAccess,
) {
    @Operation(summary = "Tiles, chart series, ranking cards and funnel for a date range (dashboard.read)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = DashboardOverviewResponse::class)
    @GetMapping
    fun overview(
        authentication: Authentication?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "false") compare: Boolean,
    ): DataResponse<DashboardOverviewResponse> {
        access.require(DashboardCallers.operatorOf(authentication), DashboardPermissions.READ)
        return Response.ok(assembler.overview(parser.parse(from, to), compare))
    }

    @Operation(summary = "One ranking with more rows than the overview shows (dashboard.read)")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = DashboardRankingResponse::class)
    @GetMapping("/rankings/{key}")
    fun ranking(
        authentication: Authentication?,
        @PathVariable key: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): DataResponse<DashboardRankingResponse> {
        access.require(DashboardCallers.operatorOf(authentication), DashboardPermissions.READ)
        return Response.ok(assembler.ranking(key, parser.parse(from, to), limit.coerceIn(1, MAX_LIMIT)))
    }

    private companion object {
        const val MAX_LIMIT = 500
    }
}
