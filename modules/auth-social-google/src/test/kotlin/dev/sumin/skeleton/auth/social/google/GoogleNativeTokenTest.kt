package dev.sumin.skeleton.auth.social.google

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.PlainJWT
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthIdTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderNotFoundException
import dev.sumin.skeleton.auth.social.oauth.codeExchange
import dev.sumin.skeleton.auth.social.testing.FakeIdp
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 앱 SDK 의 구글 ID 토큰 검증 (스펙 §3) — 진짜 구글 없이 FakeIdp 의 JWKS 로 */
class GoogleNativeTokenTest {
    private val webClientId = "web-client-id.apps.example.com"
    private lateinit var idp: FakeIdp
    private lateinit var provider: GoogleOAuthProvider

    @BeforeTest
    fun setUp() {
        idp = FakeIdp(clientId = webClientId)
        idp.stub("/oauth2/v3/certs") { 200 to JWKSet(idp.rsaKeys.map { it.toPublicJWK() }).toString() }
        provider = GoogleOAuthProvider(
            FakeIdp.httpClient(),
            AuthSocialProperties.Provider(enabled = true, clientId = webClientId, clientSecret = "google-secret", apiBaseUrl = idp.base),
        )
    }

    @AfterTest
    fun tearDown() = idp.close()

    private fun token(
        sub: String = "g-sub-1", aud: Any = webClientId, iss: String = "https://accounts.google.com", exp: Instant = Instant.now().plusSeconds(600), nonce: String? = null,
        extra: Map<String, Any> = mapOf("email" to "native@gmail.com", "email_verified" to true, "name" to "Native User", "picture" to "https://example.com/p.png"),
    ) = idp.sign(idp.claims(sub = sub, nonce = nonce, aud = aud, iss = iss, exp = exp, extra = extra))

    private fun verify(idToken: String, nonce: String? = null) = provider.verifyNativeToken(OAuthNativeToken(idToken, nonce))

    @Test
    fun `google takes an id token, and a nonce only when the request has one`() {
        assertEquals(NativeTokenKind.ID_TOKEN, provider.nativeToken)
        assertEquals(NonceMode.SUPPORTED, provider.nonce)
    }

    @Test
    fun `a valid id token becomes the profile - both google issuer spellings are accepted`() {
        val profile = verify(token())
        assertEquals("google", profile.provider)
        assertEquals("g-sub-1", profile.providerUserId)
        assertEquals("native@gmail.com", profile.email)
        assertEquals("Native User", profile.displayName)
        assertEquals("https://example.com/p.png", profile.avatarUrl)
        assertTrue(profile.emailVerified)
        assertTrue(profile.emailAuthoritative)
        assertEquals("g-sub-1", verify(token(iss = "accounts.google.com")).providerUserId)
    }

    @Test
    fun `email authority follows the same rule as the code flow - hd is read from the signed token`() {
        val workspace = mapOf("email" to "me@corp.example.com", "email_verified" to true)
        assertFalse(verify(token(extra = workspace)).emailAuthoritative, "non-Gmail without hd")
        assertTrue(verify(token(extra = workspace + ("hd" to "corp.example.com"))).emailAuthoritative, "hd equals the address domain")
        assertFalse(verify(token(extra = workspace + ("hd" to "other.example.com"))).emailAuthoritative, "hd of another domain")
        assertFalse(verify(token(extra = mapOf("email" to "x@gmail.com", "email_verified" to false))).emailAuthoritative, "unverified")
    }

    @Test
    fun `a nonce in the request must match the token, and none in the request is not checked`() {
        assertEquals("g-sub-1", verify(token(nonce = "nonce-abcdefgh"), "nonce-abcdefgh").providerUserId)
        assertFailsWith<OAuthIdTokenInvalidException> { verify(token(nonce = "nonce-abcdefgh"), "another-nonce-1") }
        assertFailsWith<OAuthIdTokenInvalidException> { verify(token(nonce = null), "nonce-abcdefgh") }
        assertEquals("g-sub-1", verify(token(nonce = "nonce-abcdefgh"), null).providerUserId)
    }

    @Test
    fun `a token for another app, another issuer or past its expiry is rejected`() {
        assertFailsWith<OAuthIdTokenInvalidException> { verify(token(aud = "someone-elses-client")) }
        assertFailsWith<OAuthIdTokenInvalidException> { verify(token(aud = listOf("someone-elses-client", "yet-another"))) }
        assertFailsWith<OAuthIdTokenInvalidException> { verify(token(iss = "https://evil.example.com")) }
        assertFailsWith<OAuthIdTokenInvalidException> { verify(token(exp = Instant.now().minusSeconds(3600))) }
    }

    @Test
    fun `an app-only backend needs no client secret - the id token verifies and the code flow is closed`() {
        val appOnly = GoogleOAuthProvider(
            FakeIdp.httpClient(),
            AuthSocialProperties.Provider(enabled = true, clientId = webClientId, apiBaseUrl = idp.base),
        )
        assertEquals("g-sub-1", appOnly.verifyNativeToken(OAuthNativeToken(token(), null)).providerUserId)
        assertFalse(appOnly.codeFlow)
        assertFailsWith<OAuthProviderNotFoundException> { appOnly.codeExchange("code", null, null, null) }
        assertTrue(provider.codeFlow)
    }

    @Test
    fun `a forged signature, an unsigned token and a symmetric one are rejected and never leak the token`() {
        val attacker = RSAKeyGenerator(2048).keyID(idp.rsaKeys.first().keyID).generate()   // same kid, other key
        val forged = idp.sign(idp.claims(aud = webClientId, iss = "https://accounts.google.com"), attacker)
        val unsigned = PlainJWT(idp.claims(aud = webClientId, iss = "https://accounts.google.com")).serialize()
        val hmac = idp.signHs256(idp.claims(aud = webClientId, iss = "https://accounts.google.com"), "google-secret-google-secret-google-secret")
        for (bad in listOf(forged, unsigned, hmac, "not-a-jwt")) {
            val ex = assertFailsWith<OAuthIdTokenInvalidException> { verify(bad) }
            assertFalse(ex.toString().contains(bad) || (ex.message ?: "").contains(bad))
        }
    }
}
