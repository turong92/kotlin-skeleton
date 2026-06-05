package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.ApplicationException
import org.springframework.http.HttpStatus

class OAuthProviderNotFoundException(providerId: String) : ApplicationException(
    status = HttpStatus.NOT_FOUND,
    title = "OAuth provider not found",
    message = "OAuth provider is not enabled or does not exist",
)

class OAuthInvalidAuthorizationCodeException(providerId: String) : ApplicationException(
    status = HttpStatus.UNAUTHORIZED,
    title = "Invalid OAuth authorization code",
    message = "OAuth authorization code is invalid",
)

class OAuthProviderGatewayException(providerId: String, cause: Throwable) : ApplicationException(
    status = HttpStatus.BAD_GATEWAY,
    title = "OAuth provider request failed",
    message = "OAuth provider request failed",
    cause = cause,
)

class OAuthAccountLinkNotFoundException(provider: String, providerUserId: String) : ApplicationException(
    status = HttpStatus.CONFLICT,
    title = "OAuth account is not linked",
    message = "OAuth account is not linked to an internal account",
)

class OAuthLinkedAccountNotFoundException(accountId: String) : ApplicationException(
    status = HttpStatus.CONFLICT,
    title = "Linked account not found",
    message = "Linked internal account was not found",
)
