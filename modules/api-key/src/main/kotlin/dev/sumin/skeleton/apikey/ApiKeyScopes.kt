package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.common.apikey.ApiKeyScopeDeclaration

data class DeclaredApiKeyScope(val key: String, val module: String)

/** 모듈 · 앱이 [ApiKeyScopeDeclaration] 빈으로 선언한 범위 모음 - 발급은 이 안에서만 고를 수 있다 */
class ApiKeyScopes(private val declarations: () -> List<ApiKeyScopeDeclaration>) {
    fun all(): List<DeclaredApiKeyScope> =
        declarations().flatMap { d -> d.scopes.map { DeclaredApiKeyScope(it, d.module) } }.distinctBy { it.key }.sortedBy { it.key }

    fun isDeclared(key: String): Boolean = all().any { it.key == key }
}
