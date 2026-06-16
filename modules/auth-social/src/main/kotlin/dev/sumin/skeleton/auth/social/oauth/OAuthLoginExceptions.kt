package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.ApplicationException

class OAuthProviderNotFoundException(providerId: String) : ApplicationException(
    errorCode = AuthSocialErrorCode.PROVIDER_NOT_FOUND,
    message = "OAuth provider is not enabled or does not exist",
)

class OAuthInvalidAuthorizationCodeException(providerId: String) : ApplicationException(
    errorCode = AuthSocialErrorCode.INVALID_AUTHORIZATION_CODE,
    message = "OAuth authorization code is invalid",
)

class OAuthProviderGatewayException(providerId: String, cause: Throwable) : ApplicationException(
    errorCode = AuthSocialErrorCode.PROVIDER_GATEWAY_ERROR,
    message = "OAuth provider request failed",
    cause = cause,
)

class OAuthAccountLinkNotFoundException(provider: String, providerUserId: String) : ApplicationException(
    errorCode = AuthSocialErrorCode.ACCOUNT_LINK_NOT_FOUND,
    message = "OAuth account is not linked to an internal account",
)

class OAuthLinkedAccountNotFoundException(accountId: String) : ApplicationException(
    errorCode = AuthSocialErrorCode.LINKED_ACCOUNT_NOT_FOUND,
    message = "Linked internal account was not found",
)
