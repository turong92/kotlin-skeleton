package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 이벤트 운영 권한 - 만들기 · 고치기 · 켜고 끄기. 기본으로 받는 역할은 없다 - 전권 역할(`*`)만. */
object CampaignPermissions {
    const val MANAGE = "campaign.manage"

    fun declaration() = PermissionDeclaration("campaign", listOf(DeclaredPermission(MANAGE)))
}
