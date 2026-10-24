package dev.sumin.skeleton.auth.principal

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef

data class CurrentPrincipal(
    val accountId: String,
    val username: String? = null,
    val email: String? = null,
    val roles: Set<String> = emptySet(),
    /** 이 토큰을 낸 세션(`auth-session`). 없으면 null — 세션 목록에서 "현재 기기" 를 가린다. `auth` 만 쓰는 앱의 JSON 은 예전 그대로(키 자체가 없다) */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val sessionId: String? = null,
    /**
     * 공용 검사기(`OperatorAccess`)의 결과 — 사전순, 전권이면 `["*"]`. 응답(`/auth/me` · 로그인 · 갱신)에서는 늘 채워지고(없으면 `[]`),
     * 토큰에서 읽은 보안 주체에서는 null(키 자체가 없다). 화면이 메뉴를 숨기는 데만 쓴다 — 진짜 검사는 서버
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val permissions: List<String>? = null,
) {
    fun hasRole(role: String): Boolean = roles.contains(role)

    fun withPermissions(access: OperatorAccess?): CurrentPrincipal =
        copy(permissions = access?.permissionsOf(OperatorRef(accountId, roles))?.sorted().orEmpty())
}
