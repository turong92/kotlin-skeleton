package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties

class OAuthProviderRegistry(
    providers: List<OAuthProvider>,
    private val properties: AuthSocialProperties,
) {
    private val providersById = providers.associateBy { it.providerId.normalizeProviderId() }

    fun findEnabled(providerId: String): OAuthProvider? {
        val normalized = providerId.normalizeProviderId()
        if (properties.providers[normalized]?.enabled != true) {
            return null
        }
        return providersById[normalized]
    }

    private fun String.normalizeProviderId(): String = trim().lowercase()
}
