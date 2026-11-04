package dev.sumin.skeleton.auth.social.apple

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.common.GlobalExceptionHandler
import io.swagger.v3.oas.annotations.Operation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AppleRevokeControllerTest {
    private val apple = AppleFixture()

    @AfterTest fun close() = apple.close()

    private fun mvc(withApple: Boolean = true): MockMvc {
        val registry = OAuthProviderRegistry(if (withApple) listOf(apple.provider) else emptyList(), AuthSocialProperties())
        return MockMvcBuilders.standaloneSetup(AppleRevokeController(registry)).setControllerAdvice(GlobalExceptionHandler()).build()
    }

    private fun revoke(mvc: MockMvc, body: String = """{"authorizationCode":"c-1"}""") =
        mvc.perform(post("/api/v1/auth/social/apple/revoke").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `a signed-in user's code revokes the refresh token at Apple and the answer is 204`() {
        apple.stubToken(refreshToken = "rt-1")
        apple.idp.stub("/auth/revoke", 200, "")
        revoke(mvc()).andExpect(status().isNoContent)
        assertEquals("rt-1", apple.idp.requests.single { it.path == "/auth/revoke" }.form()["token"])
    }

    @Test
    fun `Apple rejecting the code is 401 AUTH_SOCIAL_INVALID_AUTHORIZATION_CODE and nothing is revoked`() {
        apple.stubToken(status = 400, body = """{"error":"invalid_grant"}""")
        revoke(mvc()).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH_SOCIAL.INVALID_AUTHORIZATION_CODE"))
        assertEquals(0, apple.idp.count("/auth/revoke"))
    }

    @Test
    fun `an Apple outage - on the exchange or on the revocation - is 502`() {
        apple.stubToken(status = 503, body = "{}")
        revoke(mvc()).andExpect(status().isBadGateway).andExpect(jsonPath("$.code").value("AUTH_SOCIAL.PROVIDER_GATEWAY_ERROR"))
        apple.stubToken()
        apple.idp.stub("/auth/revoke", 500, "{}")
        revoke(mvc()).andExpect(status().isBadGateway)
    }

    @Test
    fun `a missing code is 400 and a project without Apple is 404`() {
        revoke(mvc(), "{}").andExpect(status().isBadRequest)
        revoke(mvc(), """{"authorizationCode":" "}""").andExpect(status().isBadRequest)
        revoke(mvc(withApple = false)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("AUTH_SOCIAL.PROVIDER_NOT_FOUND"))
        assertEquals(0, apple.idp.requests.size)
    }

    @Test
    fun `the operation is described for the OpenAPI document`() {
        val method = AppleRevokeController::class.java.getMethod("revoke", AppleRevokeRequest::class.java)
        assertNotNull(method.getAnnotation(Operation::class.java))
    }
}
