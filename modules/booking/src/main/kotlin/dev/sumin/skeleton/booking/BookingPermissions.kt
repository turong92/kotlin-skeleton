package dev.sumin.skeleton.booking

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 운영자 경로의 권한. 기본 역할이 없어 전권(`ADMIN`)만 가진다 — 역할 관리에서 나눠 준다 */
object BookingPermissions {
    /** 풀 만들기 · 고치기 · 멈추기 · 자리 넣기 · 막기/풀기 */
    const val MANAGE = "booking.manage"

    /** 풀 목록 · 현황 */
    const val READ = "booking.read"

    val declaration = PermissionDeclaration("booking", listOf(DeclaredPermission(MANAGE), DeclaredPermission(READ)))
}
