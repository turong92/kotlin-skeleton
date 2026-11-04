package dev.sumin.skeleton.auth.social.apple

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import dev.sumin.skeleton.auth.social.testing.FakeIdp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

const val BUNDLE_ID = "com.example.app"
const val TEAM_ID = "TEAM123456"
const val KEY_ID = "KEY1234567"

/** 시험이 시간을 앞으로 돌린다 */
class MutableClock(var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

fun sha256Hex(raw: String): String =
    MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** `.p8` 파일 모양(PKCS#8 PEM)으로 — 시험 EC 키를 쓴다 */
fun pemOf(key: ECPrivateKey): String =
    "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) + "\n-----END PRIVATE KEY-----\n"

/** 시험용 애플: 가짜 서버(FakeIdp) 하나가 JWKS(`/auth/keys`)를 서고, 토큰은 시험 RSA 키가 서명하며, client_secret 은 시험 EC 키로 만들어진다 */
class AppleFixture(val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    val idp = FakeIdp(clientId = BUNDLE_ID)
    val privateKeyPem: String = pemOf(idp.ecKey.toECPrivateKey())
    val properties = AppleProperties(clientId = BUNDLE_ID, teamId = TEAM_ID, keyId = KEY_ID, privateKey = privateKeyPem, baseUrl = idp.base)
    val provider = AppleOAuthProvider(FakeIdp.httpClient(), properties, clock)

    init {
        idp.stub("/auth/keys") { 200 to JWKSet(idp.rsaKeys.map { it.toPublicJWK() }).toString() }
    }

    /** 앱이 SDK 에서 받은 identityToken — nonce 클레임은 원문의 SHA-256 hex */
    fun identityToken(
        sub: String = "001234.abcdef.5678",
        rawNonce: String? = "raw-nonce-0123456789",
        extra: Map<String, Any> = mapOf("email" to "abc123@privaterelay.appleid.com", "email_verified" to "true", "is_private_email" to "true"),
        aud: String = BUNDLE_ID,
        iss: String = idp.base,
        exp: Instant = Instant.now().plusSeconds(600),
    ): String = idp.sign(claims(sub, rawNonce?.let(::sha256Hex), extra, aud, iss, exp))

    fun claims(sub: String = "001234.abcdef.5678", hashedNonce: String? = null, extra: Map<String, Any> = emptyMap(), aud: String = BUNDLE_ID, iss: String = idp.base, exp: Instant = Instant.now().plusSeconds(600)): JWTClaimsSet =
        idp.claims(sub = sub, nonce = hashedNonce, aud = aud, iss = iss, exp = exp, extra = extra)

    /** `/auth/token` 의 가짜 응답. 토큰 요청 본문은 `idp.requests` 에 남는다 */
    fun stubToken(idToken: String? = identityToken(), refreshToken: String? = "refresh-token-secret", status: Int = 200, body: String? = null) {
        idp.stub("/auth/token") {
            status to (body ?: buildString {
                append("""{"access_token":"access-token-secret","token_type":"Bearer","expires_in":3600""")
                refreshToken?.let { append(""","refresh_token":"$it"""") }
                idToken?.let { append(""","id_token":"$it"""") }
                append("}")
            })
        }
    }

    override fun close() = idp.close()
}
