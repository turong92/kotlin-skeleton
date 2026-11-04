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
    /** 콜백이 받은 OAuth `state` — 토큰 요청에 요구하는 제공자(Naver)에게만 전달된다. 나머지는 무시 */
    val state: String? = null,
) {
    override fun toString() = "OAuthSocialLoginRequest(authorizationCode=<redacted>, codeVerifier=<redacted>)"
}

/** 앱의 네이티브 SDK 토큰으로 로그인 — 제공자가 받는 토큰 종류([dev.sumin.skeleton.auth.social.oauth.NativeTokenKind])에 맞는 필드 하나만 보낸다. `GET /auth/methods` 의 `nativeToken` 이 null 인 제공자는 404 */
data class OAuthSocialTokenRequest(
    /** `nativeToken=id_token` 인 제공자(구글 · 애플)의 ID 토큰 */
    val idToken: String? = null,
    /** `nativeToken=access_token` 인 제공자(카카오 · 네이버)의 액세스 토큰 */
    val accessToken: String? = null,
    /** 앱이 만든 nonce **원문** (애플은 필수 – 서버가 SHA-256 hex 로 바꿔 비교한다. 구글은 보냈을 때만 비교) */
    val nonce: String? = null,
    /** 애플이 첫 로그인 때 SDK 응답에만 주는 이름 – 새 계정의 닉네임 후보로만 쓴다. 다른 제공자는 무시 */
    val displayName: String? = null,
) {
    override fun toString() = "OAuthSocialTokenRequest(idToken=<redacted>, accessToken=<redacted>)"
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
            state = request.state,
        )

    fun loginWithToken(
        provider: String,
        request: OAuthSocialTokenRequest,
    ): AuthTokenResponse =
        loginService.loginWithNativeToken(
            providerId = provider,
            idToken = request.idToken,
            accessToken = request.accessToken,
            nonce = request.nonce,
            displayName = request.displayName,
        )
}
