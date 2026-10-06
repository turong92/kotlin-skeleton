package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.util.function.Supplier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OidcAutoConfigurationTest {
    private val idp = FakeIdp(clientId = "2001234567", clientSecret = "0123456789abcdef0123456789abcdef")   // LINE: 채널 ID 는 숫자, 채널 시크릿은 32자 hex
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OidcAutoConfiguration::class.java))
        .withBean(ExternalHttpClient::class.java, Supplier { FakeIdp.httpClient() })

    @AfterTest fun close() = idp.close()

    private val p = "skeleton.auth-social-oidc.providers"

    @Test
    fun `nothing is configured - no provider bean and no network`() {
        runner.run { ctx ->
            assertTrue(ctx.getBeansOfType(OAuthProvider::class.java).isEmpty())
            assertTrue(idp.requests.isEmpty())
        }
    }

    @Test
    fun `a provider without a client id is not created - even with an issuer that would fail discovery - and nothing is requested`() {
        idp.discoveryStatus = 500
        runner.withPropertyValues("$p.microsoft.issuer=${idp.issuer}", "$p.line.client-secret=only-a-secret").run { ctx ->
            assertTrue(ctx.getBeansOfType(OAuthProvider::class.java).isEmpty())
            assertTrue(idp.requests.isEmpty(), "disabled means silent")
        }
    }

    @Test
    fun `one module instance serves several providers - line from its preset and microsoft by discovery`() {
        runner.withPropertyValues(
            "$p.line.client-id=2001234567", "$p.line.client-secret=0123456789abcdef0123456789abcdef",
            "$p.line.authorization-endpoint=${idp.base}/authorize", "$p.line.token-endpoint=${idp.base}/token", "$p.line.jwks-uri=${idp.base}/jwks", "$p.line.issuer=${idp.issuer}",
            "$p.microsoft.issuer=${idp.issuer}", "$p.microsoft.client-id=ms-client", "$p.microsoft.client-secret=ms-secret",
        ).run { ctx ->
            val providers = ctx.getBeansOfType(OAuthProvider::class.java).values.associateBy { it.providerId }
            assertEquals(setOf("line", "microsoft"), providers.keys)
            assertEquals(PkceMode.REQUIRED, providers.getValue("line").pkce)
            assertEquals(NonceMode.REQUIRED, providers.getValue("line").nonce)
            assertEquals(PkceMode.SUPPORTED, providers.getValue("microsoft").pkce)
            assertTrue(providers.values.all { it.autoEnabled })
            assertEquals(1, idp.count("/.well-known/openid-configuration"), "only microsoft needs discovery; LINE's endpoints are explicit")
        }
    }

    @Test
    fun `an enabled provider whose discovery fails stops the application with a readable message`() {
        idp.discoveryStatus = 503
        runner.withPropertyValues("$p.microsoft.issuer=${idp.issuer}", "$p.microsoft.client-id=ms-client", "$p.microsoft.client-secret=ms-secret").run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure)
            val messages = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "OIDC discovery failed for provider 'microsoft'" in it && idp.issuer in it && "client-id" in it }, messages.toString())
        }
    }

    @Test
    fun `LINE end to end - only client id and secret plus the email scope - the HS256 token of web login is verified with the channel secret and the email is never verified`() {
        runner.withPropertyValues(
            "$p.line.client-id=2001234567", "$p.line.client-secret=0123456789abcdef0123456789abcdef", "$p.line.scopes=openid,profile,email",
            // 진짜 LINE 주소 대신 로컬 가짜 서버로 (프리셋의 칸을 속성이 이긴다)
            "$p.line.authorization-endpoint=${idp.base}/authorize", "$p.line.token-endpoint=${idp.base}/token", "$p.line.jwks-uri=${idp.base}/jwks", "$p.line.issuer=${idp.issuer}",
        ).run { ctx ->
            val line = ctx.getBeansOfType(OAuthProvider::class.java).values.single()
            assertEquals(listOf("openid", "profile", "email"), line.authorize?.scopes)
            val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            val nonce = "line-nonce-0123456789"
            // LINE 문서의 ID 토큰 페이로드 모양: iss · sub(U + 32 hex) · aud(채널 ID) · exp · iat · nonce · amr · name · picture · email
            val claims = idp.claims(
                sub = "U1234567890abcdef1234567890abcdef", nonce = nonce, iss = idp.issuer,
                extra = mapOf("amr" to listOf("pwd"), "name" to "Taro Line", "picture" to "https://profile.line-scdn.net/abc", "email" to "taro@example.com"),
            )
            idp.issue("line-code", idp.s256(verifier), "https://app.example.com/cb/line", idp.signHs256(claims, secret = "0123456789abcdef0123456789abcdef"))
            val profile = line.fetchProfile(OAuthCodeExchange("line-code", "https://app.example.com/cb/line", verifier, nonce))
            assertEquals("U1234567890abcdef1234567890abcdef", profile.providerUserId)
            assertEquals("taro@example.com", profile.email)
            assertFalse(profile.emailVerified, "LINE states no email_verified claim and no guarantee - not verified")
            assertEquals("Taro Line", profile.displayName)
            assertEquals("https://profile.line-scdn.net/abc", profile.avatarUrl)
            assertEquals(0, idp.count("/userinfo"), "LINE preset never calls userinfo")
        }
    }

    @Test
    fun `LINE without the email permission - no email claim - is simply address-less`() {
        runner.withPropertyValues(
            "$p.line.client-id=2001234567", "$p.line.client-secret=0123456789abcdef0123456789abcdef",
            "$p.line.authorization-endpoint=${idp.base}/authorize", "$p.line.token-endpoint=${idp.base}/token", "$p.line.jwks-uri=${idp.base}/jwks", "$p.line.issuer=${idp.issuer}",
        ).run { ctx ->
            val line = ctx.getBeansOfType(OAuthProvider::class.java).values.single()
            assertEquals(listOf("openid", "profile"), line.authorize?.scopes)
            val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            idp.issue("c", idp.s256(verifier), null, idp.signHs256(idp.claims(sub = "Uabc", nonce = "nnnnnnnnnn", extra = mapOf("name" to "No Mail")), secret = "0123456789abcdef0123456789abcdef"))
            val profile = line.fetchProfile(OAuthCodeExchange("c", null, verifier, "nnnnnnnnnn"))
            assertEquals(null, profile.email)
            assertFalse(profile.emailVerified)
        }
    }
}
