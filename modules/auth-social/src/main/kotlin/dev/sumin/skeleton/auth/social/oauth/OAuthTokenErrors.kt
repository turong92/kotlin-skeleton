package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.http.ExternalHttpStatusException

/**
 * 토큰 엔드포인트의 4xx 를 가른다 (RFC 6749 §5.2 의 `error` 필드, 없으면 HTTP 상태):
 *  - 429 · 5xx: 원래 예외 그대로 (재시도 가능한 제공자 쪽 문제 → 502)
 *  - `invalid_client` · `unauthorized_client` · `invalid_scope` · `unsupported_grant_type`, 또는 `error` 없는 401 · 403: **우리 설정**(client id · secret · scope)이 거부됐다 → 사용자의 잘못이 아니므로 [IllegalStateException] (502)
 *  - 그 밖의 400 (`invalid_grant` · `invalid_request`): 코드가 틀림 · 만료 · 이미 씀 → 401. 검증기를 보냈고 오류 설명이 검증기 · PKCE 를 가리키면 400 [OAuthPkceException]
 */
object OAuthTokenErrors {
    private val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")
    private val DESCRIPTION_FIELD = Regex("\"error_description\"\\s*:\\s*\"([^\"]*)\"")
    private val CLIENT_ERRORS = setOf("invalid_client", "unauthorized_client", "invalid_scope", "unsupported_grant_type")
    private val VERIFIER_TEXT = Regex("verifier|pkce|code_challenge", RegexOption.IGNORE_CASE)

    fun map(providerId: String, ex: ExternalHttpStatusException, verifierSent: Boolean): RuntimeException {
        val status = ex.upstreamStatus ?: return ex
        if (status == 429 || status >= 500) return ex
        val error = ERROR_FIELD.find(ex.upstreamBody)?.groupValues?.get(1)
        val description = DESCRIPTION_FIELD.find(ex.upstreamBody)?.groupValues?.get(1).orEmpty()
        if (error in CLIENT_ERRORS || (error == null && (status == 401 || status == 403))) {
            return IllegalStateException("OAuth provider '$providerId' rejected our client configuration (HTTP $status, error=${error ?: "none"}): check client id, client secret, scopes and redirect URI")
        }
        if (verifierSent && VERIFIER_TEXT.containsMatchIn(description)) return OAuthPkceException(providerId)
        return OAuthInvalidAuthorizationCodeException(providerId)
    }
}
