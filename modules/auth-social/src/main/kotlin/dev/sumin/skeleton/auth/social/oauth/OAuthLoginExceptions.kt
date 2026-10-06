package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.ApplicationException

class OAuthProviderNotFoundException(providerId: String) : ApplicationException(
    errorCode = AuthSocialErrorCode.PROVIDER_NOT_FOUND,
    message = "OAuth provider is not enabled or does not exist",
)

/** 제공자가 코드를 거부했다 (틀림 · 만료 · 이미 씀). 하위 클래스 — [OAuthIdTokenInvalidException] — 도 "증명이 틀렸다" 로 같은 자리에서 잡힌다 */
open class OAuthInvalidAuthorizationCodeException(
    providerId: String,
    errorCode: AuthSocialErrorCode = AuthSocialErrorCode.INVALID_AUTHORIZATION_CODE,
    message: String = "OAuth authorization code is invalid",
) : ApplicationException(
    errorCode = errorCode,
    message = message,
)

/** 제공자가 준 ID 토큰이 검증을 못 넘었다 (서명 · iss · aud · exp · nonce). [reason] 은 운영자 로그용, 응답에는 나가지 않는다 */
class OAuthIdTokenInvalidException(providerId: String, val reason: String) : OAuthInvalidAuthorizationCodeException(
    providerId = providerId,
    errorCode = AuthSocialErrorCode.ID_TOKEN_INVALID,
    message = "OAuth ID token rejected",
)

/** PKCE 가 필요한데 검증기가 없거나 모양이 틀렸다 — 제공자를 부르기 **전에** 끊는다 (한 번 쓰는 인가 코드를 태우지 않는다) */
class OAuthPkceException(providerId: String) : ApplicationException(
    errorCode = AuthSocialErrorCode.PKCE_FAILED,
    message = "OAuth PKCE verifier is missing or malformed",
)

class OAuthNonceException(providerId: String) : ApplicationException(
    errorCode = AuthSocialErrorCode.NONCE_FAILED,
    message = "OAuth nonce is missing or malformed",
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
