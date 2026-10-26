package dev.sumin.skeleton.dashboard.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.dashboard.DashboardLayout
import dev.sumin.skeleton.dashboard.DashboardLayouts
import dev.sumin.skeleton.dashboard.DashboardPermissions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DashboardLayoutBody(val hidden: List<String>? = null, val order: List<String>? = null)

/**
 * 화면 설정(`dashboard.read`) — **호출자 자기 것만**: 경로에 계정 id 가 없고 `Authentication.name` 만 쓴다. 저장소(`dashboard-jdbc`)가 없으면 404 `DASHBOARD.LAYOUT_UNAVAILABLE`.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard/layout")
@Tag(name = "Dashboard")
class DashboardLayoutController(private val layouts: DashboardLayouts, private val access: OperatorAccess) {
    @Operation(summary = "Your own screen settings: hidden cards and tiles, card order (dashboard.read)")
    @GetMapping
    fun read(authentication: Authentication?): DataResponse<DashboardLayout> {
        val caller = DashboardCallers.operatorOf(authentication)
        access.require(caller, DashboardPermissions.READ)
        return Response.ok(layouts.read(caller!!.accountId))
    }

    @Operation(summary = "Replace your own screen settings (dashboard.read)")
    @PutMapping
    fun save(authentication: Authentication?, @RequestBody body: DashboardLayoutBody): DataResponse<DashboardLayout> {
        val caller = DashboardCallers.operatorOf(authentication)
        access.require(caller, DashboardPermissions.READ)
        return Response.ok(layouts.save(caller!!.accountId, DashboardLayout(body.hidden.orEmpty(), body.order.orEmpty())))
    }
}
