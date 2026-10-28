package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 포인트 운영 경로의 권한. 기본으로 받는 역할은 없다 — 전권 역할(`*`)만 */
object PointPermissions {
    /** 계정의 잔액 · 내역 보기와 조정 */
    const val MANAGE = "point.manage"

    val declaration = PermissionDeclaration("point", listOf(DeclaredPermission(MANAGE)))
}
