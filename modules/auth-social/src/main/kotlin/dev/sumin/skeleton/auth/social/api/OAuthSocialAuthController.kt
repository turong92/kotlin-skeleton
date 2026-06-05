package dev.sumin.skeleton.auth.social.api

import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService

data class OAuthSocialLoginRequest(
    val authorizationCode: String,
    val redirectUri: String? = null,
)

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
        )
}
