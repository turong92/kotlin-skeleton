package dev.sumin.skeleton.common.apikey

import dev.sumin.skeleton.common.authz.PermissionKeys

/**
 * 이 핸들러(메서드 또는 컨트롤러 클래스)를 **API 키로도 열어 준다** — 키는 [value] 범위를 가졌을 때만 닿는다.
 * 표시가 없는 핸들러는 키로 닿지 않는다(기본 거부). `api-key` 모듈이 없으면 아무 일도 하지 않는다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ApiKeyScope(val value: String)

/**
 * 모듈 · 앱이 「키로 고를 수 있는 범위」를 선언하는 빈 — [dev.sumin.skeleton.common.authz.PermissionDeclaration] 처럼 판정하는 모듈에 의존하지 않고 선언만 한다.
 * 키 규칙은 권한 키([PermissionKeys])와 같은 모양(`notes.read`)이고 운영 권한과는 다른 이름 공간이다.
 */
data class ApiKeyScopeDeclaration(
    val module: String,
    val scopes: List<String>,
) {
    init {
        scopes.forEach { require(PermissionKeys.isValid(it)) { "invalid API key scope: $it" } }
    }
}
