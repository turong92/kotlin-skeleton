package dev.sumin.skeleton.auth.social

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.social.api.OAuthSocialAuthController
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.InMemoryOAuthAccountLinkRepository
import dev.sumin.skeleton.auth.social.oauth.LinkedAccountOnlyOAuthAccountProvisioningPolicy
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountLink
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "skeleton.auth-social.providers.fake.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    OAuthSocialAuthControllerIntegrationTest.FakeProviderConfiguration::class,
)
class OAuthSocialAuthControllerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `POST social login with fake provider returns bearer token and principal`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code","redirectUri":"http://localhost:3000/auth/callback/fake"}"""
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.accessToken") { isNotEmpty() }
            jsonPath("$.value.tokenType") { value("Bearer") }
            jsonPath("$.value.expiresAt") { isNotEmpty() }
            jsonPath("$.value.principal.accountId") { value("acc_user") }
            jsonPath("$.value.principal.username") { value("user") }
            jsonPath("$.value.principal.email") { value("user@example.com") }
            jsonPath("$.value.principal.roles") { value(hasItem("USER")) }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `GET me accepts bearer token issued by social login`() {
        val loginResponse = mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val accessToken = JsonPath.read<String>(loginResponse, "$.value.accessToken")

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $accessToken")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.accountId") { value("acc_user") }
            jsonPath("$.value.roles") { value(hasItem("USER")) }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `POST social login with invalid code returns bad gateway ApiError for provider failure`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"bad-code"}"""
        }.andExpect {
            status { isBadGateway() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(502) }
            jsonPath("$.code") { value("AUTH_SOCIAL.PROVIDER_GATEWAY_ERROR") }
            jsonPath("$.title") { value("OAuth provider request failed") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `POST social login with unlinked provider account returns conflict ApiError`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-unlinked-code"}"""
        }.andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(409) }
            jsonPath("$.code") { value("AUTH_SOCIAL.ACCOUNT_LINK_NOT_FOUND") }
            jsonPath("$.title") { value("OAuth account is not linked") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `POST social login uses repository roles instead of provider profile roles`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.principal.roles") { value(org.hamcrest.Matchers.contains("USER")) }
            jsonPath("$.value.principal.roles") { value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ADMIN"))) }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProviderConfiguration {
        @Bean
        fun fakeOAuthProvider(): OAuthProvider =
            object : OAuthProvider {
                override val providerId: String = "fake"

                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
                    when (authorizationCode) {
                        "valid-user-code" -> OAuthUserProfile(
                            provider = "fake",
                            providerUserId = "fake_user",
                            email = "provider@example.com",
                            username = "provider-user",
                            displayName = "Provider User",
                        )
                        "valid-unlinked-code" -> OAuthUserProfile(
                            provider = "fake",
                            providerUserId = "unlinked_user",
                            email = "unlinked@example.com",
                            username = "unlinked-user",
                            displayName = "Unlinked User",
                        )
                        else -> throw IllegalArgumentException("invalid fake code")
                    }
            }
    }
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    OAuthSocialAuthControllerOverrideIntegrationTest.OverrideControllerConfiguration::class,
)
class OAuthSocialAuthControllerOverrideIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `custom social auth controller bean handles social login without default conflict`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"override-code"}"""
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.principal.accountId") { value("acc_override") }
            jsonPath("$.value.principal.username") { value("override-user") }
            jsonPath("$.value.principal.email") { value("override@example.com") }
            jsonPath("$.value.principal.roles") { value(hasItem("OVERRIDE")) }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class OverrideControllerConfiguration {
        @Bean
        fun overrideOAuthSocialAuthController(): OAuthSocialAuthController =
            OAuthSocialAuthController(overrideLoginService())

        private fun overrideLoginService(): OAuthSocialLoginService {
            val provider = object : OAuthProvider {
                override val providerId: String = "fake"

                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
                    if (authorizationCode != "override-code") {
                        throw IllegalArgumentException("invalid override code")
                    }
                    return OAuthUserProfile(
                        provider = "fake",
                        providerUserId = "override_user",
                        email = "provider-override@example.com",
                        username = "provider-override",
                        displayName = "Provider Override",
                    )
                }
            }
            val providerRegistry = OAuthProviderRegistry(
                providers = listOf(provider),
                properties = AuthSocialProperties(
                    providers = mapOf("fake" to AuthSocialProperties.Provider(enabled = true)),
                ),
            )
            val linkRepository = InMemoryOAuthAccountLinkRepository(
                links = listOf(
                    OAuthAccountLink(provider = "fake", providerUserId = "override_user", accountId = "acc_override"),
                ),
            )
            val accountRepository = InMemoryAuthAccountRepository(
                accounts = listOf(
                    AuthAccount(
                        accountId = "acc_override",
                        username = "override-user",
                        email = "override@example.com",
                        passwordHash = "hash",
                        roles = setOf("OVERRIDE"),
                    ),
                ),
            )
            val tokenResponseFactory = AuthTokenResponseFactory(
                JwtTokenService(
                    AuthProperties.Jwt(
                        issuer = "override-test",
                        secret = "override-test-jwt-secret-32-bytes",
                        accessTokenTtl = Duration.ofMinutes(15),
                    ),
                    Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC),
                ),
            )
            return OAuthSocialLoginService(
                providerRegistry = providerRegistry,
                provisioningPolicy = LinkedAccountOnlyOAuthAccountProvisioningPolicy(linkRepository),
                accountRepository = accountRepository,
                tokenResponseFactory = tokenResponseFactory,
            )
        }
    }
}
