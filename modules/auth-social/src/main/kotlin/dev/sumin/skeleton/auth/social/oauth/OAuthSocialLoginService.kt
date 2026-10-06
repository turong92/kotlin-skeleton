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
    ): AuthTokenResponse {
        val provider = providerRegistry.findEnabled(providerId)
            ?: throw OAuthProviderNotFoundException(providerId)
        // 제공자를 부르기 전에 PKCE · nonce 전제를 본다 — 틀리면 한 번 쓰는 인가 코드가 타지 않는다
        val exchange = provider.codeExchange(authorizationCode, redirectUri, codeVerifier, nonce)

        val profile = try {
            provider.fetchProfile(exchange)
        } catch (ex: OAuthInvalidAuthorizationCodeException) {
            throw ex
        } catch (ex: RuntimeException) {
            throw OAuthProviderGatewayException(provider.providerId, ex)
        }
        val canonicalProfile = profile.copy(provider = provider.providerId)

        val accountId = provisioningPolicy.resolveOrCreateAccount(canonicalProfile)
            ?: throw OAuthAccountLinkNotFoundException(canonicalProfile.provider, canonicalProfile.providerUserId)

        val account = accountRepository.findBy(AccountIdentifier(accountId = accountId))
            ?: throw OAuthLinkedAccountNotFoundException(accountId)

        return tokenResponseFactory.issue(account)
    }
}
