package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.common.ApplicationException
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OAuthSocialLoginServiceTest {
    private val accountRepository = InMemoryAuthAccountRepository(
        listOf(
            AuthAccount(
                accountId = "acc_user",
                username = "user",
                email = "user@example.com",
                passwordHash = "hash",
                roles = setOf("USER"),
            ),
        ),
    )
    private val tokenFactory = AuthTokenResponseFactory(
        JwtTokenService(
            AuthProperties.Jwt(
                issuer = "test-issuer",
                secret = "test-jwt-secret-change-me-32-bytes",
                accessTokenTtl = Duration.ofMinutes(15),
            ),
            Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC),
        ),
    )

    @Test
    fun `login returns auth token response for linked account`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        val response = service.login(
            providerId = "fake",
            authorizationCode = "valid-code",
            redirectUri = "http://localhost:3000/auth/callback/fake",
        )

        assertEquals("Bearer", response.tokenType)
        assertEquals("acc_user", response.principal.accountId)
        assertEquals(setOf("USER"), response.principal.roles)
    }

    @Test
    fun `login resolves account link under selected provider when profile provider differs`() {
        val service = service(
            provider = FakeOAuthProvider(
                providerId = "fake",
                validCode = "valid-code",
                providerUserId = "fake_user",
                profileProvider = "other",
            ),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        val response = service.login("fake", "valid-code", null)

        assertEquals("acc_user", response.principal.accountId)
    }

    @Test
    fun `login rejects unknown provider`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = emptyList(),
        )

        assertFailsWith<OAuthProviderNotFoundException> {
            service.login("missing", "valid-code", null)
        }
    }

    @Test
    fun `login rejects unlinked provider account`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "unlinked_user"),
            links = emptyList(),
        )

        assertFailsWith<OAuthAccountLinkNotFoundException> {
            service.login("fake", "valid-code", null)
        }
    }

    @Test
    fun `login rejects stale account link`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "missing")),
        )

        assertFailsWith<OAuthLinkedAccountNotFoundException> {
            service.login("fake", "valid-code", null)
        }
    }

    @Test
    fun `login maps invalid authorization code to unauthorized exception`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        assertFailsWith<OAuthInvalidAuthorizationCodeException> {
            service.login("fake", "bad-code", null)
        }
    }

    @Test
    fun `login maps provider application exception to gateway exception`() {
        val service = service(
            provider = ApplicationExceptionOAuthProvider(
                providerId = "fake",
                exception = ProviderApplicationException("provider-secret-detail"),
            ),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        assertFailsWith<OAuthProviderGatewayException> {
            service.login("fake", "valid-code", null)
        }
    }

    @Test
    fun `oauth exception messages do not expose provider or account internals`() {
        val gateway = OAuthProviderGatewayException("fake-secret", RuntimeException("provider-secret-detail"))

        assertEquals("OAuth provider is not enabled or does not exist", OAuthProviderNotFoundException("fake-secret").message)
        assertEquals("OAuth authorization code is invalid", OAuthInvalidAuthorizationCodeException("fake-secret").message)
        assertEquals("OAuth provider request failed", gateway.message)
        assertEquals("provider-secret-detail", gateway.cause?.message)
        assertEquals(
            "OAuth account is not linked to an internal account",
            OAuthAccountLinkNotFoundException("fake", "provider-user-secret").message,
        )
        assertEquals(
            "Linked internal account was not found",
            OAuthLinkedAccountNotFoundException("internal-account-secret").message,
        )
    }

    private fun service(
        provider: OAuthProvider,
        links: List<OAuthAccountLink>,
    ): OAuthSocialLoginService {
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf(provider.providerId to AuthSocialProperties.Provider(enabled = true)),
            ),
        )
        val linkRepository = InMemoryOAuthAccountLinkRepository(links)
        return OAuthSocialLoginService(
            providerRegistry = registry,
            provisioningPolicy = LinkedAccountOnlyOAuthAccountProvisioningPolicy(linkRepository),
            accountRepository = accountRepository,
            tokenResponseFactory = tokenFactory,
        )
    }

    private class FakeOAuthProvider(
        override val providerId: String,
        private val validCode: String,
        private val providerUserId: String,
        private val profileProvider: String = providerId,
    ) : OAuthProvider {
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
            if (authorizationCode != validCode) {
                throw OAuthInvalidAuthorizationCodeException(providerId)
            }
            return OAuthUserProfile(
                provider = profileProvider,
                providerUserId = providerUserId,
                email = "provider@example.com",
                username = "provider-user",
                displayName = "Provider User",
            )
        }
    }

    private class ApplicationExceptionOAuthProvider(
        override val providerId: String,
        private val exception: ApplicationException,
    ) : OAuthProvider {
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
            throw exception
        }
    }

    private class ProviderApplicationException(message: String) : ApplicationException(
        status = HttpStatus.BAD_REQUEST,
        title = "Provider application exception",
        message = message,
    )
}
