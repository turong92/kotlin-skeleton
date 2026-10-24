package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 약관 운영자 읽기(모든 주체의 동의 사건 · 매니페스트 상태). 기본으로 받는 역할은 없다 — 전권 역할(`*`)만 */
object LegalPermissions {
    const val READ = "legal.read"

    fun declaration() = PermissionDeclaration("legal", listOf(DeclaredPermission(READ)))
}
