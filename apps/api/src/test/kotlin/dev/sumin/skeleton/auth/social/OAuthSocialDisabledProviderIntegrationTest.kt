package dev.sumin.skeleton.auth.social

import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "skeleton.auth-social.providers.fake.enabled=false",
    ],
)
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    OAuthSocialDisabledProviderIntegrationTest.FakeProviderConfiguration::class,
)
class OAuthSocialDisabledProviderIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `POST social login with disabled provider returns not found ApiError`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code"}"""
        }.andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(404) }
            jsonPath("$.code") { value("AUTH_SOCIAL.PROVIDER_NOT_FOUND") }
            jsonPath("$.title") { value("OAuth provider not found") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProviderConfiguration {
        @Bean
        fun fakeOAuthProvider(): OAuthProvider =
            object : OAuthProvider {
                override val providerId: String = "fake"

                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
                    OAuthUserProfile(
                        provider = "fake",
                        providerUserId = "fake_user",
                        email = "provider@example.com",
                        username = "provider-user",
                        displayName = "Provider User",
                    )
            }
    }
}
