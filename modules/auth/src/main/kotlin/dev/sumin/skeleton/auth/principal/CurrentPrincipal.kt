package dev.sumin.skeleton.auth.principal

import com.fasterxml.jackson.annotation.JsonInclude

data class CurrentPrincipal(
    val accountId: String,
    val username: String? = null,
    val email: String? = null,
    val roles: Set<String> = emptySet(),
    /** 이 토큰을 낸 세션(`auth-session`). 없으면 null — 세션 목록에서 "현재 기기" 를 가린다. `auth` 만 쓰는 앱의 JSON 은 예전 그대로(키 자체가 없다) */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val sessionId: String? = null,
) {
    fun hasRole(role: String): Boolean = roles.contains(role)
}
