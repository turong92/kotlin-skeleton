package dev.sumin.skeleton.auth.social.apple

import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderGatewayException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderNotFoundException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.NoContentOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AppleRevokeRequest(
    /** 탈퇴 직전에 애플 시트를 한 번 더 띄워 받은 authorizationCode */
    @field:NotBlank @field:Size(max = 2048) val authorizationCode: String?,
) {
    override fun toString() = "AppleRevokeRequest(authorizationCode=<redacted>)"
}

/** 탈퇴 철회 HTTP — 로그인한 사용자만 부른다 (공개 경로에 올리지 않는다). [AppleAutoConfiguration] 이 애플이 켜진 앱에서만 등록한다 */
@RestController
@RequestMapping("/api/v1/auth/social/apple")
@Tag(name = "auth-social")
class AppleRevokeController(private val providers: OAuthProviderRegistry) {
    @Operation(
        summary = "Revoke the Sign in with Apple tokens of the signed-in user (call it before POST /account/delete)",
        description = "Body authorizationCode: a fresh code from the Apple sheet. The server exchanges it and revokes the refresh token at Apple, then answers 204.",
        responses = [
            ApiResponse(responseCode = "204", description = "Revoked"),
            ApiResponse(responseCode = "401", description = "AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE: Apple rejected the code"),
            ApiResponse(responseCode = "404", description = "AUTH_SOCIAL.PROVIDER_NOT_FOUND: Sign in with Apple is not enabled for this project"),
            ApiResponse(responseCode = "502", description = "AUTH_SOCIAL.PROVIDER_GATEWAY_ERROR: Apple failed or our Apple settings were refused"),
        ],
    )
    @PostMapping("/revoke")
    @NoContentOperation
    fun revoke(@Valid @RequestBody request: AppleRevokeRequest): ResponseEntity<Void> {
        val apple = providers.findEnabled("apple") as? AppleOAuthProvider ?: throw OAuthProviderNotFoundException("apple")
        try {
            apple.revoke(request.authorizationCode!!)
        } catch (ex: OAuthInvalidAuthorizationCodeException) {
            throw ex
        } catch (ex: RuntimeException) {
            throw OAuthProviderGatewayException("apple", ex)
        }
        return Response.noContent()
    }
}
