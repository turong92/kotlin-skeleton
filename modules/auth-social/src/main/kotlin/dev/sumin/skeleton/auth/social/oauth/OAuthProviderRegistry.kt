package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties

class OAuthProviderRegistry(
    providers: List<OAuthProvider>,
    private val properties: AuthSocialProperties,
) {
    private val providersById = providers.associateByUniqueProviderId()

    fun findEnabled(providerId: String): OAuthProvider? {
        val normalized = providerId.normalizeProviderId()
        if (properties.providers[normalized]?.enabled != true) {
            return null
        }
        return providersById[normalized]
    }

    private fun String.normalizeProviderId(): String = trim().lowercase()

    private fun List<OAuthProvider>.associateByUniqueProviderId(): Map<String, OAuthProvider> {
        val grouped = groupBy { it.providerId.normalizeProviderId() }
        val duplicate = grouped.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Duplicate OAuth provider id '${duplicate!!.key}'"
        }
        return grouped.mapValues { it.value.single() }
    }
}
