package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.http.HttpStatus

/** PKCE · nonce 전제 검사: 제공자가 무엇을 요구하느냐에 따라 제공자 호출 **전에** 400 으로 끊거나, 검증기를 그대로 넘기거나, 버린다 */
class OAuthPkceTest {
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk" // RFC 7636 부록 B 의 예시 값 (43자)
    private val calls = mutableListOf<OAuthCodeExchange>()

    private fun provider(pkce: PkceMode, nonce: NonceMode = NonceMode.UNSUPPORTED) = object : OAuthProvider {
        override val providerId = "fake"
        override val pkce = pkce
        override val nonce = nonce
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile = error("새 경로만 쓴다")
        override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
            calls += exchange
            return OAuthUserProfile("fake", "fake_user", null, null, "Fake")
        }
    }

    private fun service(provider: OAuthProvider): OAuthSocialLoginService {
        val links = InMemoryOAuthAccountLinkRepository(listOf(OAuthAccountLink("fake", "fake_user", "acc_user")))
        return OAuthSocialLoginService(
            providerRegistry = OAuthProviderRegistry(listOf(provider), AuthSocialProperties(mapOf("fake" to AuthSocialProperties.Provider(enabled = true)))),
            provisioningPolicy = LinkedAccountOnlyOAuthAccountProvisioningPolicy(links),
            accountRepository = InMemoryAuthAccountRepository(listOf(AuthAccount("acc_user", "user", "user@example.com", "hash", setOf("USER")))),
            tokenResponseFactory = AuthTokenResponseFactory(
                JwtTokenService(AuthProperties.Jwt(issuer = "t", secret = "test-jwt-secret-change-me-32-bytes", accessTokenTtl = Duration.ofMinutes(15)), Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC)),
            ),
        )
    }

    @Test
    fun `a REQUIRED provider without a verifier is refused with 400 AUTH SOCIAL_PKCE_FAILED before the provider is called`() {
        val ex = assertFailsWith<OAuthPkceException> { service(provider(PkceMode.REQUIRED)).login("fake", "code", "https://app/cb") }
        assertEquals("AUTH.SOCIAL_PKCE_FAILED", ex.errorCode.code)
        assertEquals(HttpStatus.BAD_REQUEST, ex.errorCode.status)
        assertEquals(0, calls.size, "nothing reached the provider, so the single-use code is not burned")
    }

    @Test
    fun `a REQUIRED provider forwards the verifier`() {
        service(provider(PkceMode.REQUIRED)).login("fake", "code", "https://app/cb", codeVerifier = verifier)
        assertEquals(OAuthCodeExchange("code", "https://app/cb", verifier, null), calls.single())
    }

    @Test
    fun `a SUPPORTED provider works without a verifier and forwards one when given`() {
        val service = service(provider(PkceMode.SUPPORTED))
        service.login("fake", "c1", null)
        service.login("fake", "c2", null, codeVerifier = verifier)
        assertNull(calls[0].codeVerifier)
        assertEquals(verifier, calls[1].codeVerifier)
    }

    @Test
    fun `an UNSUPPORTED provider never sees the verifier`() {
        service(provider(PkceMode.UNSUPPORTED)).login("fake", "c", null, codeVerifier = verifier)
        assertNull(calls.single().codeVerifier)
    }

    @Test
    fun `a malformed verifier is refused even when PKCE is only SUPPORTED`() {
        val service = service(provider(PkceMode.SUPPORTED))
        for (bad in listOf("short", "x".repeat(129), "a".repeat(42), "a".repeat(43) + " ", "가".repeat(50))) {
            assertFailsWith<OAuthPkceException>("[$bad]") { service.login("fake", "c", null, codeVerifier = bad) }
        }
        assertEquals(0, calls.size)
    }

    @Test
    fun `a REQUIRED nonce missing is refused with 400 AUTH SOCIAL_NONCE_FAILED and a given nonce is forwarded`() {
        val service = service(provider(PkceMode.UNSUPPORTED, NonceMode.REQUIRED))
        val ex = assertFailsWith<OAuthNonceException> { service.login("fake", "c", null) }
        assertEquals("AUTH.SOCIAL_NONCE_FAILED", ex.errorCode.code)
        service.login("fake", "c", null, nonce = "n-0S6_WzA2Mj")
        assertEquals("n-0S6_WzA2Mj", calls.single().nonce)
    }

    @Test
    fun `an UNSUPPORTED nonce is dropped`() {
        service(provider(PkceMode.UNSUPPORTED, NonceMode.UNSUPPORTED)).login("fake", "c", null, nonce = "n-0S6_WzA2Mj")
        assertNull(calls.single().nonce)
    }

    @Test
    fun `a provider that knows only the old two argument method still works`() {
        val legacy = object : OAuthProvider {
            override val providerId = "fake"
            override fun fetchProfile(authorizationCode: String, redirectUri: String?) = OAuthUserProfile("fake", "fake_user", null, null, "Legacy")
        }
        assertEquals("acc_user", service(legacy).login("fake", "c", null, codeVerifier = verifier).principal.accountId)
    }

    @Test
    fun `a provider that enables itself is found without the auth-social switch`() {
        val self = object : OAuthProvider {
            override val providerId = "selfish"
            override val autoEnabled = true
            override fun fetchProfile(authorizationCode: String, redirectUri: String?) = OAuthUserProfile("selfish", "s", null, null, null)
        }
        val registry = OAuthProviderRegistry(listOf(self), AuthSocialProperties())
        assertEquals(self, registry.findEnabled("Selfish"))
    }
}
