package dev.sumin.skeleton.auth.social.oidc

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.JWTParser
import com.nimbusds.jwt.SignedJWT
import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** ID 토큰이 검증을 못 넘었다. [reason] 은 운영자 로그용 (토큰 · 비밀을 담지 않는다) */
class IdTokenRejected(val reason: String) : RuntimeException(reason)

/**
 * 제공자의 JWKS 를 보관한다. 모르는 `kid` 가 오면 키 교체로 보고 **한 번** 다시 받는다 — 단 [cooldown] 안에는 다시 받지 않는다 (가짜 kid 로 제공자를 두드리지 못하게).
 * 받기 실패 시 옛 키로 계속 검증한다.
 */
class JwksCache(
    private val http: ExternalHttpClient,
    private val url: () -> String,
    private val ttl: Duration,
    private val cooldown: Duration,
    private val clock: Clock,
) {
    private var keys: JWKSet? = null
    private var fetchedAt: Instant = Instant.MIN

    @Synchronized
    fun find(kid: String?): JWK? {
        val now = clock.instant()
        if (keys == null || Duration.between(fetchedAt, now) >= ttl) refresh(now, mustSucceed = keys == null)
        lookup(kid)?.let { return it }
        if (Duration.between(fetchedAt, now) >= cooldown) {
            refresh(now, mustSucceed = false)
            return lookup(kid)
        }
        return null
    }

    private fun lookup(kid: String?): JWK? {
        val set = keys ?: return null
        if (kid != null) return set.getKeyByKeyId(kid)
        return set.keys.singleOrNull()   // kid 없는 토큰은 키가 하나뿐일 때만
    }

    private fun refresh(now: Instant, mustSucceed: Boolean) {
        try {
            val body = requireNotNull(http.get("auth-social-oidc-jwks", url(), String::class.java) { loggingTag("auth-social.oidc.jwks") }.block()) { "empty JWKS" }
            keys = JWKSet.parse(body)
            fetchedAt = now
        } catch (ex: RuntimeException) {
            if (mustSucceed) throw ex   // 키가 하나도 없는데 못 받았다 — 제공자 장애로 보고한다 (502)
        } catch (ex: ParseException) {
            if (mustSucceed) throw IllegalStateException("JWKS from ${url()} is not valid JSON Web Key Set JSON", ex)
        }
    }
}

/**
 * ID 토큰 검증 (OIDC Core §3.1.3.7): 서명(허용 알고리즘만 · HS 계열은 client secret, RS · ES 계열은 JWKS 의 같은 종류 키) → `iss` → `aud`(여럿이면 `azp`) → `exp`(+skew) → `nbf` → `nonce`(기대값이 있을 때).
 */
class IdTokenVerifier(
    private val clientId: String,
    private val clientSecret: String,
    private val algorithms: List<JWSAlgorithm>,
    private val issuer: () -> String?,
    private val jwks: JwksCache?,
    private val skew: Duration,
    private val clock: Clock,
) {
    fun verify(idToken: String, expectedNonce: String?): JWTClaimsSet {
        val parsed = try {
            JWTParser.parse(idToken)
        } catch (ex: ParseException) {
            throw IdTokenRejected("malformed token")
        }
        val jwt = parsed as? SignedJWT ?: throw IdTokenRejected("alg: unsigned or encrypted tokens are not accepted")
        val alg = jwt.header.algorithm
        if (alg !in algorithms) throw IdTokenRejected("alg ${alg.name} is not on the allow list ${algorithms.map { it.name }}")
        if (!verifySignature(jwt, alg)) throw IdTokenRejected("signature does not verify")
        val claims = try {
            jwt.jwtClaimsSet
        } catch (ex: ParseException) {
            throw IdTokenRejected("malformed claims")
        }
        checkClaims(claims, expectedNonce)
        return claims
    }

    private fun verifySignature(jwt: SignedJWT, alg: JWSAlgorithm): Boolean {
        val verifier: JWSVerifier = when {
            JWSAlgorithm.Family.HMAC_SHA.contains(alg) -> {
                if (clientSecret.isBlank()) throw IdTokenRejected("alg ${alg.name} needs the client secret")
                MACVerifier(clientSecret.toByteArray(StandardCharsets.UTF_8))
            }
            else -> {
                val cache = jwks ?: throw IdTokenRejected("alg ${alg.name} needs a jwks-uri")
                val key = cache.find(jwt.header.keyID) ?: throw IdTokenRejected("kid ${jwt.header.keyID} is not in the provider's JWKS")
                when (key) {
                    is RSAKey -> RSASSAVerifier(key)
                    is ECKey -> ECDSAVerifier(key)
                    else -> throw IdTokenRejected("alg: key type ${key.keyType} of kid ${key.keyID} is not supported")
                }
            }
        }
        return try {
            jwt.verify(verifier)
        } catch (ex: JOSEException) {
            throw IdTokenRejected("signature: ${ex.message}")
        }
    }

    private fun checkClaims(claims: JWTClaimsSet, expectedNonce: String?) {
        val expectedIssuer = issuer()
        if (expectedIssuer != null && claims.issuer != expectedIssuer) throw IdTokenRejected("iss '${claims.issuer}' is not '$expectedIssuer'")
        val aud = claims.audience
        if (clientId !in aud) throw IdTokenRejected("aud $aud does not contain our client id")
        if (aud.size > 1 && claims.getStringClaim("azp") != clientId) throw IdTokenRejected("azp: several audiences need azp = our client id")
        val now = clock.instant()
        val exp = claims.expirationTime ?: throw IdTokenRejected("exp is missing")
        if (!now.minus(skew).isBefore(exp.toInstant())) throw IdTokenRejected("exp: token expired at ${exp.toInstant()}")
        claims.notBeforeTime?.let { if (now.plus(skew).isBefore(it.toInstant())) throw IdTokenRejected("nbf: token is not valid yet") }
        if (expectedNonce != null) {
            val actual = claims.getStringClaim("nonce") ?: throw IdTokenRejected("nonce: the token has none")
            if (!MessageDigest.isEqual(actual.toByteArray(StandardCharsets.UTF_8), expectedNonce.toByteArray(StandardCharsets.UTF_8))) throw IdTokenRejected("nonce does not match")
        }
        if (claims.subject.isNullOrBlank()) throw IdTokenRejected("sub is missing")
    }
}
