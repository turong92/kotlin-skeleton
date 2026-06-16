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
    LINKED_ACCOUNT_NOT_FOUND(
        code = "AUTH_SOCIAL.LINKED_ACCOUNT_NOT_FOUND",
        status = HttpStatus.CONFLICT,
        title = "Linked account not found",
        defaultDetail = "Linked internal account was not found",
    ),
}
