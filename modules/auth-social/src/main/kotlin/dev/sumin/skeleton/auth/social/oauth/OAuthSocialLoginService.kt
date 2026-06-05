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
    ): AuthTokenResponse {
        val provider = providerRegistry.findEnabled(providerId)
            ?: throw OAuthProviderNotFoundException(providerId)

        val profile = try {
            provider.fetchProfile(authorizationCode, redirectUri)
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
