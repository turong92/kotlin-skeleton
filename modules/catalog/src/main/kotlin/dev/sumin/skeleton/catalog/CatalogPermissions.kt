package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 상품 · 가격 운영 권한. 기본으로 받는 역할은 없다 - 전권 역할(`*`)만. */
object CatalogPermissions {
    const val MANAGE = "catalog.manage"

    fun declaration() = PermissionDeclaration("catalog", listOf(DeclaredPermission(MANAGE)))
}
