package dev.sumin.skeleton.auth.social

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
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
            jsonPath("$.accessToken") { isNotEmpty() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresAt") { isNotEmpty() }
            jsonPath("$.principal.accountId") { value("acc_user") }
            jsonPath("$.principal.username") { value("user") }
            jsonPath("$.principal.email") { value("user@example.com") }
            jsonPath("$.principal.roles") { value(hasItem("USER")) }
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

        val accessToken = JsonPath.read<String>(loginResponse, "$.accessToken")

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $accessToken")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value("acc_user") }
            jsonPath("$.roles") { value(hasItem("USER")) }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProviderConfiguration {
        @Bean
        fun fakeOAuthProvider(): OAuthProvider =
            object : OAuthProvider {
                override val providerId: String = "fake"

                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
                    if (authorizationCode != "valid-user-code") {
                        throw IllegalArgumentException("invalid fake code")
                    }
                    return OAuthUserProfile(
                        provider = "fake",
                        providerUserId = "fake_user",
                        email = "provider@example.com",
                        username = "provider-user",
                        displayName = "Provider User",
                    )
                }
            }
    }
}
