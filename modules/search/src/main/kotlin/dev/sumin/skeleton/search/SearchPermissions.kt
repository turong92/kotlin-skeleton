package dev.sumin.skeleton.search

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 검색 운영 권한 — 종류별 현황 · 재색인. 기본으로 받는 역할은 없다 — 전권 역할(`*`)만. */
object SearchPermissions {
    const val MANAGE = "search.manage"

    fun declaration() = PermissionDeclaration("search", listOf(DeclaredPermission(MANAGE)))
}
