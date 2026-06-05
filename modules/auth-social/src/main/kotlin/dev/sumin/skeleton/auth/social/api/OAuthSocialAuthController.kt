package dev.sumin.skeleton.auth.social.api

import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class OAuthSocialLoginRequest(
    val authorizationCode: String,
    val redirectUri: String? = null,
)

@RestController
@RequestMapping("/api/v1/auth/social")
class OAuthSocialAuthController(
    private val loginService: OAuthSocialLoginService,
) {
    @PostMapping("/{provider}/login")
    fun login(
        @PathVariable provider: String,
        @RequestBody request: OAuthSocialLoginRequest,
    ): AuthTokenResponse =
        loginService.login(
            providerId = provider,
            authorizationCode = request.authorizationCode,
            redirectUri = request.redirectUri,
        )
}
