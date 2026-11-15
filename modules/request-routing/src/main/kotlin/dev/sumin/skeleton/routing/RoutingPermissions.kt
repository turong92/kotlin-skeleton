package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 기본 역할이 없어 전권(`ADMIN`)만 가진다 — 역할 관리에서 나눠 준다 */
object RoutingPermissions {
    /** 요청에 라우팅 표시를 실을 수 있다 */
    const val USE = "routing.use"

    /** 로컬 창을 열고 닫는다 */
    const val WINDOW = "routing.window"

    val declaration = PermissionDeclaration("request-routing", listOf(DeclaredPermission(USE), DeclaredPermission(WINDOW)))
}
