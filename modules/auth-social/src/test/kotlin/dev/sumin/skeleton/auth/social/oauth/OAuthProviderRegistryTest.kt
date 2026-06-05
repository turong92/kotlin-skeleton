package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthProviderRegistryTest {
    @Test
    fun `findEnabled returns provider only when provider exists and is enabled`() {
        val provider = FakeOAuthProvider("fake")
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf("fake" to AuthSocialProperties.Provider(enabled = true)),
            ),
        )

        assertEquals(provider, registry.findEnabled("fake"))
    }

    @Test
    fun `findEnabled normalizes provider id`() {
        val provider = FakeOAuthProvider("fake")
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf("fake" to AuthSocialProperties.Provider(enabled = true)),
            ),
        )

        assertEquals(provider, registry.findEnabled(" Fake "))
    }

    @Test
    fun `findEnabled returns null for disabled provider`() {
        val provider = FakeOAuthProvider("fake")
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf("fake" to AuthSocialProperties.Provider(enabled = false)),
            ),
        )

        assertNull(registry.findEnabled("fake"))
    }

    @Test
    fun `findEnabled returns null for unknown provider`() {
        val registry = OAuthProviderRegistry(
            providers = emptyList(),
            properties = AuthSocialProperties(),
        )

        assertNull(registry.findEnabled("missing"))
    }

    private class FakeOAuthProvider(
        override val providerId: String,
    ) : OAuthProvider {
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
            OAuthUserProfile(
                provider = providerId,
                providerUserId = "provider-user",
                email = "provider@example.com",
                username = "provider-user",
                displayName = "Provider User",
            )
    }
}
