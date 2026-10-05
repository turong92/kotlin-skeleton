package dev.sumin.skeleton.auth.principal

data class CurrentPrincipal(
    val accountId: String,
    val username: String? = null,
    val email: String? = null,
    val roles: Set<String> = emptySet(),
    /** 이 토큰을 낸 세션(`auth-session`). 없으면 null — 세션 목록에서 "현재 기기" 를 가린다 */
    val sessionId: String? = null,
) {
    fun hasRole(role: String): Boolean = roles.contains(role)
}
