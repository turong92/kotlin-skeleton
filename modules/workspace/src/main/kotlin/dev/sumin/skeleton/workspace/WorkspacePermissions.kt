package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 워크스페이스 운영 권한 - 목록 · 검색 · 상세 읽기. 기본으로 받는 역할은 없다 - 전권 역할(`*`)만. */
object WorkspacePermissions {
    const val READ = "workspace.read"

    fun declaration() = PermissionDeclaration("workspace", listOf(DeclaredPermission(READ)))
}
