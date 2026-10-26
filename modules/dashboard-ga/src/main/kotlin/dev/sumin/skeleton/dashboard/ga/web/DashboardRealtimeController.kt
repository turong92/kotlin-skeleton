package dev.sumin.skeleton.dashboard.ga.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardRow
import dev.sumin.skeleton.dashboard.DashboardPermissions
import dev.sumin.skeleton.dashboard.ga.GaSource
import dev.sumin.skeleton.dashboard.web.DashboardCallers
import dev.sumin.skeleton.dashboard.web.DashboardFreshnessResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DashboardRealtimeResponse(
    val status: DashboardResult.Status,
    val freshness: DashboardFreshnessResponse?,
    val activeUsers: Long,
    val pages: List<DashboardRow>,
    val countries: List<DashboardRow>,
)

/**
 * 현재 방문자(`GET /api/v1/admin/dashboard/realtime`, `dashboard.read`) — `dashboard` 의 컨트롤러가 아니라 GA 모듈이 연다. 모듈이 없으면 404 이고 프런트는 「지금 N명」 배지를 숨긴다.
 * 관리 API 스위치(`skeleton.dashboard.admin.enabled`)가 켜져 있을 때만 등록된다. 키가 없으면 200 에 `status: NOT_CONNECTED`.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
class DashboardRealtimeController(private val source: GaSource, private val access: OperatorAccess) {
    @GetMapping("/realtime")
    fun realtime(authentication: Authentication?): DataResponse<DashboardRealtimeResponse> {
        access.require(DashboardCallers.operatorOf(authentication), DashboardPermissions.READ)
        val result = source.realtime()
        val snapshot = result.data
        return Response.ok(
            DashboardRealtimeResponse(
                status = result.status,
                freshness = result.freshness?.let { DashboardFreshnessResponse(it.kind, it.asOf) },
                activeUsers = snapshot?.activeUsers ?: 0,
                pages = snapshot?.pages.orEmpty(),
                countries = snapshot?.countries.orEmpty(),
            ),
        )
    }
}
