package dev.sumin.skeleton.payment.googleplay

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.time.TimeProvider
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Pub/Sub push 가 `Authorization: Bearer` 로 보내는 OIDC 토큰을 확인한다. 구글 공개키(JWKS, [GooglePlayProperties.certsUrl])로 RS256 서명을 확인한 뒤
 * `iss`(`accounts.google.com` 또는 `https://accounts.google.com`) · `aud`(설정) · `email`(설정한 push 서비스 계정) · `email_verified` · 만료를 본다. 하나라도 틀리면 false.
 *
 * 공개키는 한 시간 캐시하고, 모르는 `kid` 가 오면 키가 바뀐 것일 수 있어 다시 받는다 - 단 **분당 한 번만**(위조 요청이 구글 호출을 늘리지 못하게).
 * 키를 받지 못했는데 아는 키도 없으면 던진다(HTTP 5xx 로 답해 Pub/Sub 이 다시 보내게 한다 - 위조로 취급해 버리지 않는다).
 */
internal class GoogleOidcVerifier(
    private val http: ExternalHttpClient,
    private val properties: GooglePlayProperties,
    private val time: TimeProvider,
) {
    private var keys: JWKSet? = null
    private var fetchedAt: Instant = Instant.MIN
    private val certsUri: URI = URI.create(properties.resolvedCertsUrl)
    private val errorMapper = GooglePlayErrorMapper(properties.providerId)

    fun verify(authorization: String?): Boolean {
        val raw = authorization?.trim()?.takeIf { it.regionMatches(0, BEARER, 0, BEARER.length, ignoreCase = true) }?.substring(BEARER.length)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val jwt = try {
            SignedJWT.parse(raw)
        } catch (_: Exception) {
            return false
        }
        if (jwt.header.algorithm != JWSAlgorithm.RS256) return false
        val kid = jwt.header.keyID ?: return false
        val key = keyFor(kid) ?: return false
        if (!runCatching { jwt.verify(RSASSAVerifier(key)) }.getOrDefault(false)) return false
        val claims = jwt.jwtClaimsSet
        val expires = claims.expirationTime?.toInstant() ?: return false
        return time.now().isBefore(expires.plus(CLOCK_SKEW)) &&
            claims.issuer in ISSUERS &&
            claims.audience == listOf(properties.pubsub.audience) &&
            claims.getStringClaim("email").equals(properties.pubsub.serviceAccountEmail, ignoreCase = true) &&
            claims.getBooleanClaim("email_verified") == true
    }

    @Synchronized
    private fun keyFor(kid: String): java.security.interfaces.RSAPublicKey? {
        val now = time.now()
        val known = keys
        if (known == null || now.isAfter(fetchedAt.plus(CACHE)) || (known.getKeyByKeyId(kid) == null && now.isAfter(fetchedAt.plus(REFETCH_AFTER)))) {
            try {
                keys = fetch()
                fetchedAt = now
            } catch (e: RuntimeException) {
                if (known == null) throw e // 아는 키가 없으면 위조인지 알 수 없다
            }
        }
        return (keys?.getKeyByKeyId(kid) as? RSAKey)?.toRSAPublicKey()
    }

    private fun fetch(): JWKSet {
        val text = http.get(
            clientName = properties.clientName,
            path = certsUri.rawPath.ifEmpty { "/" },
            responseType = String::class.java,
        ) {
            baseUrl("${certsUri.scheme}://${certsUri.rawAuthority}")
            timeout(properties.lookupTimeout)
            loggingTag("payment.googleplay.certs")
            errorMapper(errorMapper)
        }.block()
        return runCatching { JWKSet.parse(text ?: "") }.getOrNull() ?: throw GooglePlayFailures.failure(properties, GooglePlayFailures.KEYS_UNAVAILABLE)
    }

    private companion object {
        const val BEARER = "Bearer "
        val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
        val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
        val CACHE: Duration = Duration.ofHours(1)
        val REFETCH_AFTER: Duration = Duration.ofMinutes(1)
    }
}
