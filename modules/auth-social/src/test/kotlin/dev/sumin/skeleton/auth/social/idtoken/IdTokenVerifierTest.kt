package dev.sumin.skeleton.auth.social.idtoken

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.PlainJWT
import dev.sumin.skeleton.auth.social.testing.FakeIdp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdTokenVerifierTest {
    private val idp = FakeIdp()
    private val clock: Clock = Clock.systemUTC()

    @AfterTest
    fun stop() = idp.close()

    private fun verifier(
        issuers: Set<String>? = setOf(idp.issuer),
        algorithms: List<JWSAlgorithm> = listOf(JWSAlgorithm.RS256),
        nonceMatches: (String, String) -> Boolean = IdTokenVerifier.EXACT_NONCE,
    ) = IdTokenVerifier(
        clientId = idp.clientId,
        clientSecret = idp.clientSecret,
        algorithms = algorithms,
        issuers = { issuers },
        jwks = JwksCache(FakeIdp.httpClient(), { "${idp.base}/jwks" }, Duration.ofHours(1), Duration.ofMinutes(1), clock),
        skew = Duration.ofSeconds(60),
        clock = clock,
        nonceMatches = nonceMatches,
    )

    private fun rejected(token: String, expectedReason: String, v: IdTokenVerifier = verifier(), nonce: String? = null) {
        val ex = assertFailsWith<IdTokenRejected> { v.verify(token, nonce) }
        assertTrue(ex.reason.contains(expectedReason), "reason '${ex.reason}' should mention '$expectedReason'")
    }

    @Test
    fun `a valid RS256 token passes`() {
        val claims = verifier().verify(idp.sign(idp.claims(sub = "u1")), null)
        assertEquals("u1", claims.subject)
    }

    @Test
    fun `iss may be any member of the configured set`() {
        val v = verifier(issuers = setOf("https://accounts.google.com", "accounts.google.com"))
        assertEquals("u1", v.verify(idp.sign(idp.claims(sub = "u1", iss = "accounts.google.com")), null).subject)
        assertEquals("u2", v.verify(idp.sign(idp.claims(sub = "u2", iss = "https://accounts.google.com")), null).subject)
        rejected(idp.sign(idp.claims(iss = "https://evil.example.com")), "iss", v)
    }

    @Test
    fun `nonce is compared by the given function`() {
        val raw = "raw-nonce-value-1234"
        val hashed = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val sha256Hex: (String, String) -> Boolean = { claim, expected ->
            val digest = MessageDigest.getInstance("SHA-256").digest(expected.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(claim.toByteArray(StandardCharsets.UTF_8), digest.toByteArray(StandardCharsets.UTF_8))
        }
        val v = verifier(nonceMatches = sha256Hex)
        assertEquals("sub-1", v.verify(idp.sign(idp.claims(nonce = hashed)), raw).subject)
        rejected(idp.sign(idp.claims(nonce = raw)), "nonce", v, nonce = raw)   // 원문이 그대로 실린 토큰은 SHA-256 비교를 못 넘는다
    }

    @Test
    fun `nonce defaults to an exact comparison`() {
        assertEquals("sub-1", verifier().verify(idp.sign(idp.claims(nonce = "n-12345678")), "n-12345678").subject)
        rejected(idp.sign(idp.claims(nonce = "n-12345678")), "nonce", nonce = "other-1234")
        rejected(idp.sign(idp.claims()), "nonce", nonce = "n-12345678")
    }

    @Test
    fun `alg none is rejected`() {
        val plain = PlainJWT(idp.claims()).serialize()
        rejected(plain, "unsigned")
    }

    @Test
    fun `HS256 signed with the client secret is rejected when only RS256 is allowed`() {
        rejected(idp.signHs256(idp.claims()), "allow list")
    }

    @Test
    fun `ES256 is rejected when only RS256 is allowed`() {
        rejected(idp.signEs256(idp.claims()), "allow list")
    }

    @Test
    fun `a token signed with a key the provider never published is rejected`() {
        val forged = RSAKeyGenerator(2048).keyID("k1").generate()   // 같은 kid, 다른 키
        rejected(idp.sign(idp.claims(), forged), "signature")
    }

    @Test
    fun `another audience and an expired token are rejected`() {
        rejected(idp.sign(idp.claims(aud = "someone-else")), "aud")
        rejected(idp.sign(idp.claims(exp = Instant.now().minusSeconds(3600))), "exp")
    }
}
