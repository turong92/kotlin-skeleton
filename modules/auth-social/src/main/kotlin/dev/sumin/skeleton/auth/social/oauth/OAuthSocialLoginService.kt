package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory

class OAuthSocialLoginService(
    private val providerRegistry: OAuthProviderRegistry,
    private val provisioningPolicy: OAuthAccountProvisioningPolicy,
    private val accountRepository: AuthAccountRepository,
    private val tokenResponseFactory: AuthTokenResponseFactory,
) {
    fun login(
        providerId: String,
        authorizationCode: String,
        redirectUri: String?,
        codeVerifier: String? = null,
        nonce: String? = null,
        state: String? = null,
    ): AuthTokenResponse {
        val provider = providerRegistry.findEnabled(providerId)
            ?: throw OAuthProviderNotFoundException(providerId)
        // 제공자를 부르기 전에 PKCE · nonce 전제를 본다 — 틀리면 한 번 쓰는 인가 코드가 타지 않는다
        val exchange = provider.codeExchange(authorizationCode, redirectUri, codeVerifier, nonce, state)
        return signIn(provider, provider.resolveProfile(OAuthCredential.Code(exchange)))
    }

    /**
     * 앱의 네이티브 SDK 토큰으로 로그인한다 (`POST /auth/social/{provider}/token`). 토큰 입구가 없는 제공자는 404, 필드가 안 맞으면 400 — 제공자를 부르기 전에 끊는다.
     * 그 뒤(계정 연결 · 가입 · 병합 정책)는 인가 코드 로그인과 같다 — 같은 `sub` 는 어느 쪽으로 들어와도 같은 계정이다.
     */
    fun loginWithNativeToken(
        providerId: String,
        idToken: String?,
        accessToken: String?,
        nonce: String?,
        displayName: String?,
    ): AuthTokenResponse {
        val provider = providerRegistry.findEnabled(providerId)
            ?: throw OAuthProviderNotFoundException(providerId)
        val credential = provider.nativeCredential(idToken, accessToken, nonce, displayName)
        return signIn(provider, provider.resolveProfile(credential))
    }

    private fun signIn(provider: OAuthProvider, profile: OAuthUserProfile): AuthTokenResponse {
        val canonicalProfile = profile.copy(provider = provider.providerId)

        val accountId = provisioningPolicy.resolveOrCreateAccount(canonicalProfile)
            ?: throw OAuthAccountLinkNotFoundException(canonicalProfile.provider, canonicalProfile.providerUserId)

        val account = accountRepository.findBy(AccountIdentifier(accountId = accountId))
            ?: throw OAuthLinkedAccountNotFoundException(accountId)

        return tokenResponseFactory.issue(account)
    }
}
