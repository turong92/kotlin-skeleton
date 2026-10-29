package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 배지 운영 권한 — 정의 · 수동 수여 · 회수 · 내역. 기본으로 받는 역할은 없다 — 전권 역할(`*`)만. */
object BadgePermissions {
    const val MANAGE = "badge.manage"

    fun declaration() = PermissionDeclaration("badge", listOf(DeclaredPermission(MANAGE)))
}
