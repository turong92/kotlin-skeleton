package dev.sumin.skeleton.auth.social.api

import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService

data class OAuthSocialLoginRequest(
    val authorizationCode: String,
    val redirectUri: String? = null,
    /** PKCE(S256) 검증기 — `GET /auth/methods` 의 `pkce` 가 REQUIRED 면 필수, SUPPORTED 면 `code_challenge` 를 보냈을 때 */
    val codeVerifier: String? = null,
    /** 인가 요청에 실은 `nonce` — `nonce` 가 REQUIRED 면 필수 */
    val nonce: String? = null,
) {
    override fun toString() = "OAuthSocialLoginRequest(authorizationCode=<redacted>, codeVerifier=<redacted>)"
}

class OAuthSocialAuthController(
    private val loginService: OAuthSocialLoginService,
) {
    fun login(
        provider: String,
        request: OAuthSocialLoginRequest,
    ): AuthTokenResponse =
        loginService.login(
            providerId = provider,
            authorizationCode = request.authorizationCode,
            redirectUri = request.redirectUri,
            codeVerifier = request.codeVerifier,
            nonce = request.nonce,
        )
}
