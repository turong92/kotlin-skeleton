package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

object DashboardPermissions {
    /** 대시보드의 모든 경로 읽기 */
    const val READ = "dashboard.read"

    val declaration = PermissionDeclaration("dashboard", listOf(DeclaredPermission(READ)))
}
