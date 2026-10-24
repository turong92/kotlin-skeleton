package dev.sumin.skeleton.authz

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

object AuthzPermissions {
    /** 역할 만들기 · 고치기 · 지우기, 권한 카탈로그 */
    const val ROLES = "authz.roles"

    /** 계정 모듈의 `account.roles` — 역할 목록 읽기에도 열린다 (계정 화면의 「줄 수 있는 역할」) */
    const val ACCOUNT_ROLES = "account.roles"

    val declaration = PermissionDeclaration("authz", listOf(DeclaredPermission(ROLES)))
}
