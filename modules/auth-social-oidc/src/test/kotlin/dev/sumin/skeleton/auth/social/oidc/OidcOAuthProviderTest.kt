package dev.sumin.skeleton.auth.social.oidc

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthIdTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthPkceException
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OidcOAuthProviderTest {
    private val idp = FakeIdp()
    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val nonce = "n-0S6_WzA2Mj-0S6_WzA2Mj"
    private val redirect = "https://app.example.com/cb/acme"

    @AfterTest fun close() = idp.close()

    private fun config(
        issuer: String? = idp.issuer,
        explicit: Boolean = false,
        customize: (OidcProperties.Provider) -> OidcProperties.Provider = { it },
    ): OidcProperties.Provider = customize(
        OidcProperties.Provider(
            issuer = issuer,
            authorizationEndpoint = if (explicit) "${idp.base}/authorize" else null,
            tokenEndpoint = if (explicit) "${idp.base}/token" else null,
            jwksUri = if (explicit) "${idp.base}/jwks" else null,
            userinfoEndpoint = if (explicit) "${idp.base}/userinfo" else null,
            clientId = idp.clientId, clientSecret = idp.clientSecret, redirectUri = redirect,
            tokenEndpointAuth = OidcProperties.ClientAuth.POST,
        ),
    )

    private fun provider(
        p: OidcProperties.Provider = config(),
        code: String = "acme",
        global: OidcProperties = OidcProperties(jwksRefreshCooldown = Duration.ZERO),
        clock: Clock = Clock.systemUTC(),
    ) = OidcProviders.create(code, p, global, FakeIdp.httpClient(), clock)

    private fun login(code: String, challengeOf: String? = verifier, idToken: String? = idp.sign(idp.claims(nonce = nonce, extra = mapOf("email" to "a@b.co", "email_verified" to true, "name" to "Ann", "picture" to "https://img.example.com/p.png"))), exchangeVerifier: String? = verifier, exchangeNonce: String? = nonce) {
        idp.issue(code, challengeOf?.let { idp.s256(it) }, redirect, idToken)
        last = OidcRun(code, exchangeVerifier, exchangeNonce)
    }
    private class OidcRun(val code: String, val verifier: String?, val nonce: String?)
    private var last: OidcRun? = null
    private fun OidcOAuthProvider.fetch() = fetchProfile(OAuthCodeExchange(last!!.code, redirect, last!!.verifier, last!!.nonce))

    @Test
    fun `discovery then token exchange with PKCE then ID token validation maps the standard claims`() {
        val p = provider()
        login("c1")
        val profile = p.fetch()
        assertEquals("acme", profile.provider)
        assertEquals("sub-1", profile.providerUserId)
        assertEquals("a@b.co", profile.email)
        assertEquals(true, profile.emailVerified, "email_verified claim true")
        assertEquals("Ann", profile.displayName)
        assertEquals("https://img.example.com/p.png", profile.avatarUrl)
        val form = idp.requests.first { it.path == "/token" }.form()
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "c1", "redirect_uri" to redirect, "client_id" to idp.clientId, "client_secret" to idp.clientSecret, "code_verifier" to verifier), form)
        assertEquals(1, idp.count("/.well-known/openid-configuration"))
    }

    @Test
    fun `the authorize endpoint comes from discovery and the modes are what the properties say`() {
        val p = provider(config { it.copy(pkce = PkceMode.REQUIRED, nonce = NonceMode.REQUIRED, scopes = listOf("openid", "email")) })
        assertEquals(PkceMode.REQUIRED, p.pkce)
        assertEquals(NonceMode.REQUIRED, p.nonce)
        assertEquals("${idp.base}/authorize", p.authorize?.url)
        assertEquals(listOf("openid", "email"), p.authorize?.scopes)
        assertEquals(idp.clientId, p.publicClientId)
        assertEquals(redirect, p.publicRedirectUri)
        assertTrue(p.autoEnabled)
    }

    @Test
    fun `client_secret_basic sends the credentials in the Authorization header and not in the body`() {
        idp.basicAuth = true
        val p = provider(config { it.copy(tokenEndpointAuth = OidcProperties.ClientAuth.BASIC) })
        login("c2")
        p.fetch()
        val token = idp.requests.first { it.path == "/token" }
        assertTrue(token.headers.getValue("authorization").single().startsWith("Basic "))
        assertFalse(token.form().containsKey("client_secret"))
    }

    @Test
    fun `a verifier that does not match the challenge is the PKCE error - the provider named the verifier`() {
        val p = provider()
        login("c3", exchangeVerifier = "x".repeat(43))
        assertFailsWith<OAuthPkceException> { p.fetch() }
    }

    @Test
    fun `an authorization code is single use`() {
        val p = provider()
        login("c4")
        p.fetch()
        assertFailsWith<OAuthInvalidAuthorizationCodeException> { p.fetch() }
    }

    @Test
    fun `an unknown code is a refused code`() {
        val p = provider()
        last = OidcRun("never-issued", verifier, nonce)
        assertFailsWith<OAuthInvalidAuthorizationCodeException> { p.fetch() }
    }

    @Test
    fun `our client secret refused by the provider is a gateway problem not a refused code`() {
        val p = provider(config { it.copy(clientSecret = "wrong-secret") })
        login("c5")
        assertFailsWith<IllegalStateException> { p.fetch() }
    }

    // ---- ID token validation ----

    private fun rejected(idToken: String, reason: String, p: OidcOAuthProvider = provider()) {
        login("bad-${reason.hashCode()}", idToken = idToken)
        val ex = assertFailsWith<OAuthIdTokenInvalidException>(reason) { p.fetch() }
        assertTrue(reason in ex.reason, "reason '${ex.reason}' should mention '$reason'")
    }

    @Test
    fun `a nonce that is not the one of the authorization request is rejected`() = rejected(idp.sign(idp.claims(nonce = "someone-elses-nonce-0000")), "nonce")

    @Test
    fun `an ID token without any nonce is rejected when one was expected`() = rejected(idp.sign(idp.claims(nonce = null)), "nonce")

    @Test
    fun `a token for another audience is rejected`() = rejected(idp.sign(idp.claims(nonce = nonce, aud = "other-client")), "aud")

    @Test
    fun `a token with several audiences needs azp to be our client`() {
        rejected(idp.sign(idp.claims(nonce = nonce, aud = listOf(idp.clientId, "other"))), "azp")
        login("multi", idToken = idp.sign(idp.claims(nonce = nonce, aud = listOf(idp.clientId, "other"), extra = mapOf("azp" to idp.clientId))))
        assertEquals("sub-1", provider().fetch().providerUserId)
    }

    @Test
    fun `a token from another issuer is rejected`() = rejected(idp.sign(idp.claims(nonce = nonce, iss = "https://evil.example.com")), "iss")

    @Test
    fun `an expired token is rejected - checked against the injected clock with the configured skew`() {
        val later = Clock.fixed(Instant.now().plusSeconds(3600), ZoneOffset.UTC)
        rejected(idp.sign(idp.claims(nonce = nonce)), "exp", provider(clock = later))
    }

    @Test
    fun `a token within the clock skew is still accepted`() {
        val p = provider(clock = Clock.fixed(Instant.now().plusSeconds(630), ZoneOffset.UTC))   // exp = now+600, skew 60 s
        login("skew", idToken = idp.sign(idp.claims(nonce = nonce)))
        assertEquals("sub-1", p.fetch().providerUserId)
    }

    @Test
    fun `a tampered signature is rejected`() {
        val good = idp.sign(idp.claims(nonce = nonce))
        val parts = good.split(".")
        val forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"admin","iss":"${idp.issuer}","aud":"${idp.clientId}","exp":9999999999,"nonce":"$nonce"}""".toByteArray()) + "." + parts[2]
        rejected(forged, "signature")
    }

    @Test
    fun `a token signed by a key the provider never published is rejected`() {
        val rogue = RSAKeyGenerator(2048).keyID("k1").generate()   // same kid, different key
        rejected(idp.sign(idp.claims(nonce = nonce), rogue), "signature")
    }

    @Test
    fun `an unsigned token (alg none) is rejected`() {
        val unsigned = java.util.Base64.getUrlEncoder().withoutPadding().let { b ->
            b.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + b.encodeToString(idp.claims(nonce = nonce).toString().toByteArray()) + "."
        }
        rejected(unsigned, "alg")
    }

    @Test
    fun `an algorithm that is not on the allow list is rejected even when the signature is valid - HS256 is never accepted for a provider that lists only RS256`() {
        rejected(idp.signHs256(idp.claims(nonce = nonce)), "alg")
    }

    @Test
    fun `a token signed by an unknown key id is rejected after one refresh`() {
        val other = RSAKeyGenerator(2048).keyID("zzz").generate()
        rejected(idp.sign(idp.claims(nonce = nonce), other), "kid")
        assertEquals(2, idp.count("/jwks"), "first fetch, then one refresh for the unknown kid")
    }

    // ---- JWKS cache and rotation ----

    @Test
    fun `keys are cached across logins and a rotated key is picked up by one refresh`() {
        val p = provider()
        login("r1", idToken = idp.sign(idp.claims(nonce = nonce)))
        p.fetch()
        login("r2", idToken = idp.sign(idp.claims(nonce = nonce)))
        p.fetch()
        assertEquals(1, idp.count("/jwks"), "cached")

        val rotated = RSAKeyGenerator(2048).keyID("k2").generate()
        idp.rsaKeys = listOf(rotated, idp.rsaKeys.first())          // the provider now publishes both
        login("r3", idToken = idp.sign(idp.claims(nonce = nonce), rotated))
        assertEquals("sub-1", p.fetch().providerUserId)
        assertEquals(2, idp.count("/jwks"), "one refresh for the new kid")
        login("r4", idToken = idp.sign(idp.claims(nonce = nonce), rotated))
        p.fetch()
        assertEquals(2, idp.count("/jwks"), "and cached again")
    }

    @Test
    fun `unknown kids do not hammer the provider - a refresh cooldown applies`() {
        val p = provider(global = OidcProperties(jwksRefreshCooldown = Duration.ofMinutes(5)))
        val other = RSAKeyGenerator(2048).keyID("zzz").generate()
        repeat(3) { i ->
            login("h$i", idToken = idp.sign(idp.claims(nonce = nonce), other))
            assertFailsWith<OAuthIdTokenInvalidException> { p.fetch() }
        }
        assertEquals(1, idp.count("/jwks"))
    }

    @Test
    fun `an ES256 token is verified with the published EC key`() {
        idp.servedJwks = { listOf(idp.ecKey.toPublicJWK()) }
        val p = provider(config { it.copy(idTokenAlgorithms = listOf("ES256")) })
        login("es", idToken = idp.signEs256(idp.claims(nonce = nonce)))
        assertEquals("sub-1", p.fetch().providerUserId)
    }

    // ---- HS256 (client secret) ----

    @Test
    fun `HS256 tokens are verified with the client secret when the project lists HS256`() {
        val p = provider(config { it.copy(idTokenAlgorithms = listOf("HS256")) })
        login("hs", idToken = idp.signHs256(idp.claims(nonce = nonce)))
        assertEquals("sub-1", p.fetch().providerUserId)
        login("hs2", idToken = idp.signHs256(idp.claims(nonce = nonce), secret = "another-secret-another-secret-0000"))
        assertFailsWith<OAuthIdTokenInvalidException> { p.fetch() }
    }

    // ---- claim mapping, email trust, userinfo ----

    @Test
    fun `email trust NEVER keeps the email but never calls it verified`() {
        val p = provider(config { it.copy(emailTrust = OidcProperties.EmailTrust.NEVER) })
        login("nv")
        val profile = p.fetch()
        assertEquals("a@b.co", profile.email)
        assertFalse(profile.emailVerified)
    }

    @Test
    fun `email_verified as a string true or a missing claim is read strictly`() {
        val p = provider()
        login("s1", idToken = idp.sign(idp.claims(nonce = nonce, extra = mapOf("email" to "a@b.co", "email_verified" to "true"))))
        assertTrue(p.fetch().emailVerified)
        login("s2", idToken = idp.sign(idp.claims(nonce = nonce, extra = mapOf("email" to "a@b.co"))))
        assertFalse(p.fetch().emailVerified, "no claim, no verification")
        login("s3", idToken = idp.sign(idp.claims(nonce = nonce, extra = mapOf("email" to "a@b.co", "email_verified" to false))))
        assertFalse(p.fetch().emailVerified)
    }

    @Test
    fun `claim names are configurable`() {
        val p = provider(config { it.copy(claims = OidcProperties.Claims(subject = "oid", email = "upn", emailVerified = "upn_verified", name = "display", picture = "avatar")) })
        login("cl", idToken = idp.sign(idp.claims(sub = "ignored", nonce = nonce, extra = mapOf("oid" to "oid-9", "upn" to "u@corp.example", "upn_verified" to true, "display" to "Corp U", "avatar" to "https://img.example.com/u.png"))))
        val profile = p.fetch()
        assertEquals("oid-9", profile.providerUserId)
        assertEquals("u@corp.example", profile.email)
        assertTrue(profile.emailVerified)
        assertEquals("Corp U", profile.displayName)
        assertEquals("https://img.example.com/u.png", profile.avatarUrl)
    }

    @Test
    fun `an avatar that is not https is dropped`() {
        val p = provider()
        login("av", idToken = idp.sign(idp.claims(nonce = nonce, extra = mapOf("picture" to "http://img.example.com/p.png"))))
        assertNull(p.fetch().avatarUrl)
    }

    @Test
    fun `userinfo fills what the ID token lacks and its subject must match the token's`() {
        idp.userinfoBody = { """{"sub":"sub-1","email":"ui@b.co","email_verified":true,"name":"From Userinfo"}""" }
        val p = provider()
        login("ui", idToken = idp.sign(idp.claims(nonce = nonce)))
        val profile = p.fetch()
        assertEquals("ui@b.co", profile.email)
        assertEquals("From Userinfo", profile.displayName)
        assertEquals("Bearer at-ui", idp.requests.first { it.path == "/userinfo" }.headers.getValue("authorization").single())

        idp.userinfoBody = { """{"sub":"someone-else","email":"ui@b.co","email_verified":true}""" }
        login("ui2", idToken = idp.sign(idp.claims(nonce = nonce)))
        assertFailsWith<OAuthIdTokenInvalidException> { p.fetch() }
    }

    @Test
    fun `userinfo NEVER makes no userinfo call even when the token lacks the email`() {
        val p = provider(config { it.copy(userinfo = OidcProperties.UserinfoMode.NEVER) })
        login("nu", idToken = idp.sign(idp.claims(nonce = nonce)))
        assertNull(p.fetch().email)
        assertEquals(0, idp.count("/userinfo"))
    }

    // ---- startup / discovery ----

    @Test
    fun `explicit endpoints need no discovery at all`() {
        val p = provider(config(issuer = idp.issuer, explicit = true))
        login("ex")
        p.fetch()
        assertEquals(0, idp.count("/.well-known/openid-configuration"))
    }

    @Test
    fun `discovery that fails at startup fails fast with a readable message naming the provider and the issuer`() {
        idp.discoveryStatus = 500
        val ex = assertFailsWith<IllegalStateException> { provider() }
        assertTrue("acme" in ex.message!!, ex.message)
        assertTrue(idp.issuer in ex.message!!, ex.message)
        assertTrue("explicit" in ex.message!!.lowercase() || "authorization-endpoint" in ex.message!!, "tells how to fix: ${ex.message}")
    }

    @Test
    fun `a discovery document whose issuer differs from the configured one is refused`() {
        idp.discoveryIssuer = "https://evil.example.com"
        val ex = assertFailsWith<IllegalStateException> { provider() }
        assertTrue("issuer" in ex.message!!.lowercase(), ex.message)
    }

    @Test
    fun `discovery is cached - two logins make one discovery request`() {
        val p = provider()
        login("d1"); p.fetch()
        login("d2"); p.fetch()
        assertEquals(1, idp.count("/.well-known/openid-configuration"))
    }

    @Test
    fun `a provider with neither issuer nor endpoints is a startup error naming what to set`() {
        val ex = assertFailsWith<IllegalStateException> { provider(config(issuer = null)) }
        assertTrue("issuer" in ex.message!!, ex.message)
    }

    @Test
    fun `a client id without a secret fails at startup unless the client auth is NONE`() {
        assertFailsWith<IllegalStateException> { provider(config { it.copy(clientSecret = "") }) }
        provider(config { it.copy(clientSecret = "", tokenEndpointAuth = OidcProperties.ClientAuth.NONE) })
    }

    @Test
    fun `plain http endpoints on a remote host are refused at startup - only localhost may use http`() {
        val ex = assertFailsWith<IllegalStateException> {
            provider(config(issuer = null, explicit = false) { it.copy(authorizationEndpoint = "http://idp.example.com/a", tokenEndpoint = "http://idp.example.com/t", jwksUri = "http://idp.example.com/j") })
        }
        assertTrue("https" in ex.message!!, ex.message)
    }
}
