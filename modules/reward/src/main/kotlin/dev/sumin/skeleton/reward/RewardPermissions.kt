package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 지급 건 운영 권한 — 목록 · 다시 지급 · 취소. 기본으로 받는 역할은 없다 — 전권 역할(`*`)만. */
object RewardPermissions {
    const val OPERATE = "reward.operate"

    fun declaration() = PermissionDeclaration("reward", listOf(DeclaredPermission(OPERATE)))
}
