package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

object AuthorProfilePermissions {
    /** 프로필 ↔ 계정 연결 조회(관리자) */
    const val READ = "author-profile.read"

    val declaration = PermissionDeclaration("author-profile", listOf(DeclaredPermission(READ)))
}
