package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 쿠폰 운영 권한 — 정의 · 발급 · 코드 · 회수. 기본으로 받는 역할은 없다 — 전권 역할(`*`)만. */
object CouponPermissions {
    const val MANAGE = "coupon.manage"

    fun declaration() = PermissionDeclaration("coupon", listOf(DeclaredPermission(MANAGE)))
}
