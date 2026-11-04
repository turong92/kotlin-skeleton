package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.social.api.OAuthSocialAuthController
import dev.sumin.skeleton.auth.social.api.OAuthSocialTokenRequest
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.common.FieldValidationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthNativeTokenLoginTest {
    private val accountRepository = InMemoryAuthAccountRepository(
        listOf(AuthAccount(accountId = "acc_user", username = "user", email = "user@example.com", passwordHash = "hash", roles = setOf("USER"))),
    )
    private val tokenFactory = AuthTokenResponseFactory(
        JwtTokenService(
            AuthProperties.Jwt(issuer = "test-issuer", secret = "test-jwt-secret-change-me-32-bytes", accessTokenTtl = Duration.ofMinutes(15)),
            Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC),
        ),
    )
    private val links = listOf(OAuthAccountLink(provider = "idp", providerUserId = "sub-1", accountId = "acc_user"))

    /** 구글 · 애플처럼 ID 토큰을 받는 가짜 제공자. 같은 사용자가 코드로 와도 토큰으로 와도 같은 `sub` 다 */
    private class IdTokenProvider(
        override val providerId: String = "idp",
        override val nonce: NonceMode = NonceMode.SUPPORTED,
        private val onVerify: (OAuthNativeToken) -> OAuthUserProfile = { OAuthUserProfile(providerId, "sub-1", "u@example.com", "u", it.displayName) },
    ) : OAuthProvider {
        override val nativeToken = NativeTokenKind.ID_TOKEN
        val seen = mutableListOf<OAuthNativeToken>()
        override fun fetchProfile(authorizationCode: String, redirectUri: String?) = OAuthUserProfile(providerId, "sub-1", "u@example.com", "u", null)
        override fun verifyNativeToken(token: OAuthNativeToken): OAuthUserProfile { seen += token; return onVerify(token) }
    }

    private class AccessTokenProvider : OAuthProvider {
        override val providerId = "acc"
        override val nativeToken = NativeTokenKind.ACCESS_TOKEN
        override fun fetchProfile(authorizationCode: String, redirectUri: String?) = OAuthUserProfile(providerId, "sub-1", null, null, null)
        override fun verifyNativeToken(token: OAuthNativeToken) = OAuthUserProfile(providerId, "sub-1", null, null, null)
    }

    private class CodeOnlyProvider : OAuthProvider {
        override val providerId = "codeonly"
        override fun fetchProfile(authorizationCode: String, redirectUri: String?) = OAuthUserProfile(providerId, "sub-1", null, null, null)
    }

    private fun service(vararg providers: OAuthProvider, accountLinks: List<OAuthAccountLink> = links): OAuthSocialLoginService {
        val registry = OAuthProviderRegistry(
            providers = providers.toList(),
            properties = AuthSocialProperties(providers = providers.associate { it.providerId to AuthSocialProperties.Provider(enabled = true) }),
        )
        return OAuthSocialLoginService(registry, LinkedAccountOnlyOAuthAccountProvisioningPolicy(InMemoryOAuthAccountLinkRepository(accountLinks)), accountRepository, tokenFactory)
    }

    @Test
    fun `the same sub signs in to the same account by code and by native token`() {
        val s = service(IdTokenProvider())

        val byCode = s.login("idp", "code", null)
        val byToken = s.loginWithNativeToken("idp", idToken = "id-token", accessToken = null, nonce = null, displayName = null)

        assertEquals("acc_user", byCode.principal.accountId)
        assertEquals(byCode.principal.accountId, byToken.principal.accountId)
    }

    @Test
    fun `a provider with no native entrance is not found`() {
        val s = service(CodeOnlyProvider())
        assertFailsWith<OAuthProviderNotFoundException> { s.loginWithNativeToken("codeonly", "t", null, null, null) }
        assertFailsWith<OAuthProviderNotFoundException> { s.loginWithNativeToken("missing", "t", null, null, null) }
    }

    @Test
    fun `the field that matches the provider's token kind is required and the other one is refused`() {
        val s = service(IdTokenProvider(), AccessTokenProvider())

        assertEquals("idToken", assertFailsWith<FieldValidationException> { s.loginWithNativeToken("idp", null, null, null, null) }.field)
        assertEquals("idToken", assertFailsWith<FieldValidationException> { s.loginWithNativeToken("idp", "   ", null, null, null) }.field)
        assertEquals("accessToken", assertFailsWith<FieldValidationException> { s.loginWithNativeToken("idp", "t", "a", null, null) }.field)   // 둘 다
        assertEquals("idToken", assertFailsWith<FieldValidationException> { s.loginWithNativeToken("idp", null, "a", null, null) }.field)   // 엇갈림: 맞는 필드가 없다
        assertEquals("accessToken", assertFailsWith<FieldValidationException> { s.loginWithNativeToken("acc", null, null, null, null) }.field)
        assertEquals("accessToken", assertFailsWith<FieldValidationException> { s.loginWithNativeToken("acc", "t", null, null, null) }.field)   // 엇갈림: 맞는 필드가 없다
    }

    @Test
    fun `a malformed nonce or a missing required nonce is refused before the provider is called`() {
        val optional = IdTokenProvider(nonce = NonceMode.SUPPORTED)
        val required = IdTokenProvider(providerId = "req", nonce = NonceMode.REQUIRED)
        val s = service(optional, required, accountLinks = links + OAuthAccountLink("req", "sub-1", "acc_user"))

        assertFailsWith<OAuthNonceException> { s.loginWithNativeToken("idp", "t", null, "short", null) }
        assertFailsWith<OAuthNonceException> { s.loginWithNativeToken("req", "t", null, null, null) }
        assertTrue(optional.seen.isEmpty() && required.seen.isEmpty())
    }

    @Test
    fun `nonce and display name reach the provider and a nonce the provider ignores is dropped`() {
        val p = IdTokenProvider(nonce = NonceMode.SUPPORTED)
        val ignoring = IdTokenProvider(providerId = "ign", nonce = NonceMode.UNSUPPORTED)
        val s = service(p, ignoring, accountLinks = links + OAuthAccountLink("ign", "sub-1", "acc_user"))

        s.loginWithNativeToken("idp", "t", null, "nonce-12345678", "  Jane  ")
        s.loginWithNativeToken("ign", "t", null, "nonce-12345678", null)

        assertEquals(OAuthNativeToken("t", "nonce-12345678", "Jane"), p.seen.single())
        assertNull(ignoring.seen.single().nonce)
    }

    @Test
    fun `a rejected ID token or access token keeps its own 401 and anything else becomes a gateway error`() {
        val idRejected = IdTokenProvider(providerId = "idp") { throw OAuthIdTokenInvalidException("idp", "aud mismatch") }
        val accessRejected = IdTokenProvider(providerId = "acc2") { throw OAuthAccessTokenInvalidException("acc2", "app_id mismatch") }
        val broken = IdTokenProvider(providerId = "broken") { throw IllegalStateException("jwks down") }
        val s = service(idRejected, accessRejected, broken)

        assertEquals("AUTH.SOCIAL_ID_TOKEN_INVALID", assertFailsWith<OAuthIdTokenInvalidException> { s.loginWithNativeToken("idp", "t", null, null, null) }.errorCode.code)
        val access = assertFailsWith<OAuthAccessTokenInvalidException> { s.loginWithNativeToken("acc2", "t", null, null, null) }
        assertEquals("AUTH.SOCIAL_ACCESS_TOKEN_INVALID", access.errorCode.code)
        assertEquals(401, access.errorCode.status.value())
        assertFailsWith<OAuthProviderGatewayException> { s.loginWithNativeToken("broken", "t", null, null, null) }
    }

    @Test
    fun `resolveProfile turns either credential into a profile with the same exception branches`() {
        val p = IdTokenProvider()

        assertEquals("sub-1", p.resolveProfile(OAuthCredential.Code(OAuthCodeExchange("code"))).providerUserId)
        assertEquals("sub-1", p.resolveProfile(p.nativeCredential("id", null, null, null)).providerUserId)
        val broken = IdTokenProvider { throw IllegalStateException("boom") }
        assertFailsWith<OAuthProviderGatewayException> { broken.resolveProfile(broken.nativeCredential("id", null, null, null)) }
    }

    @Test
    fun `tokens never show up in toString or in exception messages`() {
        val secret = "super-secret-token-value"
        val native = OAuthNativeToken(secret, "nonce-12345678", "Jane")
        val credential = OAuthCredential.Native(native)
        val request = OAuthSocialTokenRequest(idToken = secret, accessToken = secret, nonce = "nonce-12345678", displayName = "Jane")

        for (text in listOf(native.toString(), credential.toString(), request.toString())) {
            assertFalse(text.contains(secret), text)
            assertFalse(text.contains("nonce-12345678"), text)
        }
        val s = service(IdTokenProvider { throw OAuthIdTokenInvalidException("idp", "signature does not verify") }, AccessTokenProvider())
        val failures = listOf(
            assertFailsWith<OAuthIdTokenInvalidException> { s.loginWithNativeToken("idp", secret, null, null, null) },
            assertFailsWith<FieldValidationException> { s.loginWithNativeToken("idp", secret, secret, null, null) },
            assertFailsWith<FieldValidationException> { s.loginWithNativeToken("acc", secret, null, null, null) },
        )
        failures.forEach { assertFalse(it.message.orEmpty().contains(secret), it.message) }
    }

    @Test
    fun `the controller passes the token request through to the service`() {
        val controller = OAuthSocialAuthController(service(IdTokenProvider()))
        val response = controller.loginWithToken("idp", OAuthSocialTokenRequest(idToken = "id-token"))
        assertEquals("acc_user", response.principal.accountId)
    }
}
