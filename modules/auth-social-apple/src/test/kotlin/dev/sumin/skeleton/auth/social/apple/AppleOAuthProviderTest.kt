package dev.sumin.skeleton.auth.social.apple

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthIdTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.auth.social.oauth.resolveProfile
import dev.sumin.skeleton.auth.social.oauth.OAuthCredential
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class AppleOAuthProviderTest {
    private val clock = MutableClock()
    private val apple = AppleFixture(clock)
    private val provider get() = apple.provider
    private val rawNonce = "raw-nonce-0123456789"

    @AfterTest fun close() = apple.close()

    private fun verify(token: String, nonce: String? = rawNonce, displayName: String? = null): OAuthUserProfile =
        provider.verifyNativeToken(OAuthNativeToken(token, nonce, displayName))

    private fun assertRejected(token: String, nonce: String? = rawNonce, reasonPart: String? = null) {
        val ex = assertFailsWith<OAuthIdTokenInvalidException> { verify(token, nonce) }
        reasonPart?.let { assertTrue(it in ex.reason, ex.reason) }
    }

    // ---- 계약 ----

    @Test
    fun `an ID token entrance with a required nonce, switched on by the client id`() {
        assertEquals("apple", provider.providerId)
        assertEquals(NativeTokenKind.ID_TOKEN, provider.nativeToken)
        assertEquals(NonceMode.REQUIRED, provider.nonce)
        assertTrue(provider.autoEnabled)
        assertNull(provider.publicClientId, "the bundle id is not a web client id - /auth/methods must not offer a web button")
        assertNull(provider.authorize, "no web redirect is offered")
    }

    // ---- 네이티브 idToken ----

    @Test
    fun `a valid identity token gives the profile - sub, relay email, name only from the request`() {
        val profile = verify(apple.identityToken(), displayName = "Ann Lee")
        assertEquals("apple", profile.provider)
        assertEquals("001234.abcdef.5678", profile.providerUserId)
        assertEquals("abc123@privaterelay.appleid.com", profile.email)
        assertTrue(profile.emailVerified)
        assertTrue(profile.emailAuthoritative, "Apple's own relay address is the address of record")
        assertEquals("Ann Lee", profile.displayName)
        assertEquals(1, apple.idp.count("/auth/keys"))
    }

    @Test
    fun `email_verified may be a boolean and a token without an email is a profile without one`() {
        val boolean = verify(apple.identityToken(extra = mapOf("email" to "me@icloud.com", "email_verified" to true)))
        assertTrue(boolean.emailVerified)
        assertTrue(boolean.emailAuthoritative)
        val none = verify(apple.identityToken(extra = emptyMap()))
        assertNull(none.email)
        assertFalse(none.emailVerified)
        assertFalse(none.emailAuthoritative)
        assertNull(none.displayName)
    }

    @Test
    fun `only the relay and Apple domains are authoritative - another domain on an Apple ID is verified but not trusted for merging`() {
        for (domain in listOf("privaterelay.appleid.com", "icloud.com", "me.com", "mac.com", "ICLOUD.com")) {
            assertTrue(verify(apple.identityToken(extra = mapOf("email" to "a@$domain", "email_verified" to "true"))).emailAuthoritative, domain)
        }
        for (address in listOf("a@gmail.com", "a@example.com", "a@evil-icloud.com", "a@icloud.com.evil.example", "a@sub.icloud.com")) {
            val profile = verify(apple.identityToken(extra = mapOf("email" to address, "email_verified" to "true")))
            assertTrue(profile.emailVerified, address)
            assertFalse(profile.emailAuthoritative, address)
        }
        val unverified = verify(apple.identityToken(extra = mapOf("email" to "a@icloud.com", "email_verified" to "false")))
        assertFalse(unverified.emailVerified)
        assertFalse(unverified.emailAuthoritative)
    }

    @Test
    fun `a token for another app is rejected`() = assertRejected(apple.identityToken(aud = "com.other.app"), reasonPart = "aud")

    @Test
    fun `a token from another issuer is rejected`() = assertRejected(apple.identityToken(iss = "https://evil.example.com"), reasonPart = "iss")

    @Test
    fun `an expired token is rejected`() = assertRejected(apple.identityToken(exp = Instant.now().minusSeconds(3600)), reasonPart = "exp")

    @Test
    fun `a token signed by a key that is not in Apple's key set is rejected even with a known kid`() {
        val forged = RSAKeyGenerator(2048).keyID(apple.idp.rsaKeys.first().keyID).generate()
        assertRejected(apple.idp.sign(apple.claims(hashedNonce = sha256Hex(rawNonce)), forged), reasonPart = "signature")
    }

    @Test
    fun `alg none, HS256 with any secret and ES256 are refused before the signature is looked at`() {
        val claims = apple.claims(hashedNonce = sha256Hex(rawNonce))
        assertRejected(PlainJWT(claims).serialize())
        assertRejected(apple.idp.signHs256(claims, "any-secret-any-secret-any-secret-12345"), reasonPart = "allow list")
        assertRejected(apple.idp.signEs256(claims), reasonPart = "allow list")
        assertEquals(0, apple.idp.count("/auth/keys"), "no key was fetched for a refused algorithm")
    }

    @Test
    fun `the nonce is the SHA-256 hex of the request value - a missing token claim, a mismatch and the bare value are all rejected`() {
        assertRejected(apple.identityToken(rawNonce = null), reasonPart = "nonce")
        assertRejected(apple.identityToken(rawNonce = "another-raw-nonce-0123"), reasonPart = "nonce")
        assertRejected(apple.idp.sign(apple.claims(hashedNonce = rawNonce)), reasonPart = "nonce")   // 원문이 그대로 실린 토큰은 맞지 않는다
        assertEquals("001234.abcdef.5678", verify(apple.identityToken(rawNonce = "Another-Raw-Nonce-0123"), nonce = "Another-Raw-Nonce-0123").providerUserId)
    }

    @Test
    fun `a request without a nonce is refused here too, not only in front of the provider`() {
        assertRejected(apple.identityToken(), nonce = null, reasonPart = "nonce")
    }

    // ---- 인가 코드 교환 ----

    @Test
    fun `an authorization code is exchanged with a fresh client secret JWT, the id token is checked and the profile comes back`() {
        apple.stubToken(idToken = apple.identityToken())
        val profile = provider.fetchProfile(OAuthCodeExchange("c-1", nonce = rawNonce))
        assertEquals("001234.abcdef.5678", profile.providerUserId)
        val request = apple.idp.requests.single { it.path == "/auth/token" }
        val form = request.form()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("c-1", form["code"])
        assertEquals(BUNDLE_ID, form["client_id"])
        assertClientSecret(form.getValue("client_secret"))
        assertFalse("redirect_uri" in form, "an iOS app has no redirect URI")
    }

    @Test
    fun `a token response whose id token is for another app is rejected`() {
        apple.stubToken(idToken = apple.identityToken(aud = "com.other.app"))
        assertFailsWith<OAuthIdTokenInvalidException> { provider.fetchProfile(OAuthCodeExchange("c-1", nonce = rawNonce)) }
    }

    @Test
    fun `a token response without an id token is an Apple failure not a user mistake`() {
        apple.stubToken(idToken = null)
        assertFailsWith<IllegalStateException> { provider.fetchProfile(OAuthCodeExchange("c-1", nonce = rawNonce)) }
    }

    @Test
    fun `Apple refusing the code is a rejected proof, refusing our client is our configuration`() {
        apple.stubToken(status = 400, body = """{"error":"invalid_grant","error_description":"The code has expired or has been revoked."}""")
        assertFailsWith<OAuthInvalidAuthorizationCodeException> { provider.fetchProfile(OAuthCodeExchange("c-1", nonce = rawNonce)) }
        apple.stubToken(status = 400, body = """{"error":"invalid_client"}""")
        val ex = assertFailsWith<IllegalStateException> { provider.fetchProfile(OAuthCodeExchange("c-1", nonce = rawNonce)) }
        assertTrue("client" in ex.message!!.lowercase(), ex.message)
    }

    @Test
    fun `resolveProfile turns an Apple outage into a gateway error and keeps a rejected proof as it is`() {
        apple.stubToken(status = 503, body = "{}")
        assertFailsWith<dev.sumin.skeleton.auth.social.oauth.OAuthProviderGatewayException> {
            provider.resolveProfile(OAuthCredential.Code(OAuthCodeExchange("c-1", nonce = rawNonce)))
        }
    }

    // ---- client_secret JWT ----

    private fun assertClientSecret(jwt: String, now: Instant = clock.instant()) {
        val signed = SignedJWT.parse(jwt)
        assertEquals(JWSAlgorithm.ES256, signed.header.algorithm)
        assertEquals(KEY_ID, signed.header.keyID)
        assertTrue(signed.verify(ECDSAVerifier(apple.idp.ecKey.toPublicJWK())), "signature verifies with the test EC key")
        val c = signed.jwtClaimsSet
        assertEquals(TEAM_ID, c.issuer)
        assertEquals(BUNDLE_ID, c.subject)
        assertEquals(listOf(apple.idp.base), c.audience)
        assertEquals(now.epochSecond, c.issueTime.toInstant().epochSecond)
        assertEquals(now.epochSecond + 300, c.expirationTime.toInstant().epochSecond, "five minutes")
    }

    @Test
    fun `the client secret is made anew at every call - nothing is cached`() {
        apple.stubToken()
        provider.fetchProfile(OAuthCodeExchange("c-1", nonce = rawNonce))
        clock.now = clock.now.plusSeconds(61)
        provider.fetchProfile(OAuthCodeExchange("c-2", nonce = rawNonce))
        val secrets = apple.idp.requests.filter { it.path == "/auth/token" }.map { it.form().getValue("client_secret") }
        assertEquals(2, secrets.size)
        assertNotEquals(secrets[0], secrets[1])
        assertClientSecret(secrets[1], clock.now)
    }

    // ---- 철회 ----

    @Test
    fun `revoking exchanges the code and revokes the refresh token`() {
        apple.stubToken(refreshToken = "rt-1")
        apple.idp.stub("/auth/revoke", 200, "")
        provider.revoke("c-1")
        val revoke = apple.idp.requests.single { it.path == "/auth/revoke" }.form()
        assertEquals("rt-1", revoke["token"])
        assertEquals("refresh_token", revoke["token_type_hint"])
        assertEquals(BUNDLE_ID, revoke["client_id"])
        assertClientSecret(revoke.getValue("client_secret"))
        assertEquals("c-1", apple.idp.requests.single { it.path == "/auth/token" }.form()["code"])
    }

    @Test
    fun `a rejected code is not revoked and a token response without a refresh token is an Apple failure`() {
        apple.stubToken(status = 400, body = """{"error":"invalid_grant"}""")
        apple.idp.stub("/auth/revoke", 200, "")
        assertFailsWith<OAuthInvalidAuthorizationCodeException> { provider.revoke("c-1") }
        apple.stubToken(refreshToken = null)
        assertFailsWith<IllegalStateException> { provider.revoke("c-1") }
        assertEquals(0, apple.idp.count("/auth/revoke"))
    }

    @Test
    fun `Apple failing the revocation is an Apple failure`() {
        apple.stubToken()
        apple.idp.stub("/auth/revoke", 500, "{}")
        assertFailsWith<RuntimeException> { provider.revoke("c-1") }.also { assertFalse(it is OAuthInvalidAuthorizationCodeException) }
    }

    // ---- 비밀은 로그 · 예외에 남지 않는다 ----

    @Test
    fun `tokens, the client secret and the private key never reach a log line or an exception message`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        // 로그는 http 클라이언트의 스레드에서도 온다 — 스레드 안전한 목록에 모은다
        val events = java.util.concurrent.CopyOnWriteArrayList<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) { events += event }
        }.also { it.start() }
        // 운영 기본(루트 INFO)에 우리 코드의 DEBUG 줄까지 — 프레임워크의 DEBUG(WebClient 가 디코딩한 응답 본문을 찍는다)는 운영자가 일부러 켜는 것이라 범위 밖이다
        val before = root.level
        val ours = LoggerFactory.getLogger("dev.sumin") as Logger
        val oursBefore = ours.level
        root.level = Level.INFO
        ours.level = Level.DEBUG
        root.addAppender(appender)
        val thrown = mutableListOf<Throwable>()
        val idToken = apple.identityToken(aud = "com.other.app")
        val forged = apple.idp.sign(apple.claims(hashedNonce = sha256Hex(rawNonce)), RSAKeyGenerator(2048).keyID("k1").generate())
        try {
            fun attempt(block: () -> Unit) = try { block() } catch (ex: Throwable) { thrown += ex }
            attempt { verify(idToken) }
            attempt { verify(forged) }
            attempt { apple.stubToken(idToken = idToken); provider.fetchProfile(OAuthCodeExchange("code-secret-1", nonce = rawNonce)) }
            attempt { apple.stubToken(status = 503, body = "{}"); provider.fetchProfile(OAuthCodeExchange("code-secret-2", nonce = rawNonce)) }
            attempt { apple.stubToken(refreshToken = "rt-secret-3"); apple.idp.stub("/auth/revoke", 500, "{}"); provider.revoke("code-secret-3") }
        } finally {
            root.detachAppender(appender)
            root.level = before
            ours.level = oursBefore
        }
        assertEquals(5, thrown.size)
        val clientSecrets = apple.idp.requests.filter { it.path == "/auth/token" || it.path == "/auth/revoke" }.map { it.form().getValue("client_secret") }
        val keyBody = apple.privateKeyPem.lines().filterNot { it.startsWith("-----") }.joinToString("")
        val secrets = listOf(idToken, forged, "code-secret-1", "code-secret-2", "code-secret-3", "rt-secret-3", "refresh-token-secret", "access-token-secret", keyBody) + clientSecrets
        val seen = events.flatMap { event ->
            listOf(event.formattedMessage) + generateSequence(event.throwableProxy) { it.cause }.map { it.message.orEmpty() }
        } + thrown.flatMap { ex -> generateSequence<Throwable>(ex) { it.cause }.flatMap { sequenceOf(it.message.orEmpty(), it.toString()) }.toList() }
        for (secret in secrets) assertEquals(null, seen.firstOrNull { secret in it }?.take(200), "a secret of ${secret.length} chars leaked")
        // 입구가 프로필 · 속성도 값을 내놓지 않는다
        assertFalse(apple.properties.toString().contains(keyBody))
        assertFalse(OAuthNativeToken(idToken, rawNonce).toString().contains(idToken))
    }
}
