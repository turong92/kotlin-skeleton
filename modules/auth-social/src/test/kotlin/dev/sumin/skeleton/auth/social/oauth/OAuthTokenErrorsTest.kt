package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/** 토큰 엔드포인트 오류 본문(RFC 6749 §5.2 모양)을 "코드가 틀림" · "검증기가 틀림" · "우리 설정이 틀림" · "제공자 장애" 로 가른다 */
class OAuthTokenErrorsTest {
    private fun failure(status: Int, body: String) = ExternalHttpStatusException("c", "POST", URI("https://idp.example/token"), status, body)
    private fun map(status: Int, body: String, verifierSent: Boolean = false) = OAuthTokenErrors.map("idp", failure(status, body), verifierSent)

    @Test
    fun `invalid_grant is a refused code`() {
        assertIs<OAuthInvalidAuthorizationCodeException>(map(400, """{"error":"invalid_grant","error_description":"authorization code expired or already used"}"""))
        assertIs<OAuthInvalidAuthorizationCodeException>(map(400, """{"error":"invalid_request","error_description":"invalid authorization code"}"""))
    }

    @Test
    fun `an error text that names the verifier is the PKCE error only when we sent one`() {
        val body = """{"error":"invalid_grant","error_description":"code_verifier does not match code_challenge"}"""
        assertIs<OAuthPkceException>(map(400, body, verifierSent = true))
        assertIs<OAuthInvalidAuthorizationCodeException>(map(400, body, verifierSent = false))
    }

    @Test
    fun `our own client credentials being refused is a gateway problem not the user's wrong code`() {
        assertIs<IllegalStateException>(map(401, """{"error":"invalid_client","error_description":"Client authentication failed"}"""))
        assertIs<IllegalStateException>(map(400, """{"error":"invalid_scope"}"""))
        assertIs<IllegalStateException>(map(401, """{"title":"Unauthorized","type":"about:blank","status":401,"detail":"Unauthorized"}"""))
    }

    @Test
    fun `rate limits and provider outages stay the original retryable exception`() {
        val limited = failure(429, """{"title":"Too Many Requests","status":429}""")
        assertSame(limited, OAuthTokenErrors.map("idp", limited, false))
        val down = failure(503, "")
        assertSame(down, OAuthTokenErrors.map("idp", down, false))
    }

    @Test
    fun `an unreadable 4xx body is still a refused code`() {
        assertIs<OAuthInvalidAuthorizationCodeException>(map(400, "<html>nope</html>"))
        assertIs<OAuthInvalidAuthorizationCodeException>(map(400, ""))
    }
}
