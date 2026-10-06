package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

enum class AuthSocialErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    PROVIDER_NOT_FOUND(
        code = "AUTH_SOCIAL.PROVIDER_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "OAuth provider not found",
        defaultDetail = "OAuth provider is not enabled or does not exist",
    ),
    INVALID_AUTHORIZATION_CODE(
        code = "AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE",
        status = HttpStatus.UNAUTHORIZED,
        title = "Invalid OAuth authorization code",
        defaultDetail = "OAuth authorization code is invalid",
    ),
    PROVIDER_GATEWAY_ERROR(
        code = "AUTH_SOCIAL.PROVIDER_GATEWAY_ERROR",
        status = HttpStatus.BAD_GATEWAY,
        title = "OAuth provider request failed",
        defaultDetail = "OAuth provider request failed",
    ),
    ACCOUNT_LINK_NOT_FOUND(
        code = "AUTH_SOCIAL.ACCOUNT_LINK_NOT_FOUND",
        status = HttpStatus.CONFLICT,
        title = "OAuth account is not linked",
        defaultDetail = "OAuth account is not linked to an internal account",
    ),
    PKCE_FAILED(
        code = "AUTH.SOCIAL_PKCE_FAILED",
        status = HttpStatus.BAD_REQUEST,
        title = "OAuth PKCE verifier missing or malformed",
        defaultDetail = "This provider requires PKCE: send the codeVerifier that matches the code_challenge of the authorization request",
    ),
    NONCE_FAILED(
        code = "AUTH.SOCIAL_NONCE_FAILED",
        status = HttpStatus.BAD_REQUEST,
        title = "OAuth nonce missing or malformed",
        defaultDetail = "This provider requires a nonce: send the nonce of the authorization request",
    ),
    ID_TOKEN_INVALID(
        code = "AUTH.SOCIAL_ID_TOKEN_INVALID",
        status = HttpStatus.UNAUTHORIZED,
        title = "OAuth ID token rejected",
        defaultDetail = "The provider's ID token failed validation",
    ),
    LINKED_ACCOUNT_NOT_FOUND(
        code = "AUTH_SOCIAL.LINKED_ACCOUNT_NOT_FOUND",
        status = HttpStatus.CONFLICT,
        title = "Linked account not found",
        defaultDetail = "Linked internal account was not found",
    ),
}
