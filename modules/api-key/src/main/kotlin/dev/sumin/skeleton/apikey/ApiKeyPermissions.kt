package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** API 키 운영 경로의 권한. 기본으로 받는 역할은 없다 - 전권 역할(`*`)만 */
object ApiKeyPermissions {
    /** 모든 주인의 키 목록 보기 (계정 이름은 `account.read` 도 있을 때만) */
    const val READ = "api-key.read"

    /** 남의 키를 사유를 적고 회수 */
    const val REVOKE = "api-key.revoke"

    val declaration = PermissionDeclaration("api-key", listOf(DeclaredPermission(READ), DeclaredPermission(REVOKE)))
}
