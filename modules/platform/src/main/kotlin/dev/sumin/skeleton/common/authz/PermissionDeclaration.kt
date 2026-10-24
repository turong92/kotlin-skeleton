package dev.sumin.skeleton.common.authz

/**
 * 모듈이 자기 권한을 선언하는 빈. 권한 카탈로그는 「붙은 모듈이 가진 권한」이므로 모듈을 빼면 그 권한도 사라진다.
 * 키 규칙은 [PermissionKeys].
 */
data class PermissionDeclaration(
    val module: String,
    val permissions: List<DeclaredPermission>,
)

/** [defaultRoles] 는 `skeleton.authz.roles` 를 주지 않았을 때 이 권한을 받는 역할(예: `MODERATOR`) */
data class DeclaredPermission(
    val key: String,
    val defaultRoles: Set<String> = emptySet(),
)

object PermissionKeys {
    val PATTERN = Regex("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$")
    const val MAX_LENGTH = 64

    fun isValid(key: String) = key.length <= MAX_LENGTH && PATTERN.matches(key)
}
