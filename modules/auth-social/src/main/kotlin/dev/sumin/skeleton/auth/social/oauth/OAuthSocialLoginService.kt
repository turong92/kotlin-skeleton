package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.common.ApplicationException

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
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: RuntimeException) {
            throw OAuthProviderGatewayException(provider.providerId, ex)
        }

        val accountId = provisioningPolicy.resolveOrCreateAccount(profile)
            ?: throw OAuthAccountLinkNotFoundException(profile.provider, profile.providerUserId)

        val account = accountRepository.findBy(AccountIdentifier(accountId = accountId))
            ?: throw OAuthLinkedAccountNotFoundException(accountId)

        return tokenResponseFactory.issue(account)
    }
}
