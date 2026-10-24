package dev.sumin.skeleton.common.authz

/**
 * 운영 호출자 — `platform` 은 spring-security 를 모르므로 각 모듈이 `Authentication` 을 풀어 이 값으로 넘긴다.
 * [tokenRoles] 는 토큰의 역할 이름(`ADMIN`, 접두사 없음)이다. 저장소를 다시 읽는 구현(`account`)은 이것을 보지 않는다.
 */
data class OperatorRef(
    val accountId: String,
    val tokenRoles: Set<String> = emptySet(),
    val ip: String? = null,
)
