package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class DiscoveryDocument(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val userinfoEndpoint: String?,
    val jwksUri: String?,
)

/** 엔드포인트 URL 은 https 여야 한다 (개발용 localhost · 127.0.0.1 만 http 허용) */
internal fun requireSecureUrl(what: String, url: String) {
    val uri = runCatching { URI.create(url) }.getOrElse { throw IllegalStateException("$what is not a valid URL: $url") }
    val local = uri.host == "localhost" || uri.host == "127.0.0.1"
    check(uri.scheme == "https" || (uri.scheme == "http" && local)) { "$what must be an https URL (http is allowed for localhost only): $url" }
}

/**
 * `<issuer>/.well-known/openid-configuration` 을 받아 검증하고 [ttl] 동안 보관한다. 다시 받다 실패하면 **옛 문서를 계속 쓴다** (제공자의 짧은 장애가 로그인 전체를 막지 않게).
 * 문서의 `issuer` 가 설정과 다르면 거부한다 (OIDC Discovery §4.3 — 다른 서버의 문서를 믿지 않는다).
 */
class OidcDiscovery(
    private val http: ExternalHttpClient,
    private val issuer: String,
    private val ttl: Duration,
    private val clock: Clock,
) {
    private var cached: DiscoveryDocument? = null
    private var fetchedAt: Instant = Instant.MIN

    val url: String get() = issuer.trimEnd('/') + "/.well-known/openid-configuration"

    @Synchronized
    fun document(): DiscoveryDocument {
        val now = clock.instant()
        cached?.let { if (Duration.between(fetchedAt, now) < ttl) return it }
        return try {
            fetch().also { cached = it; fetchedAt = now }
        } catch (ex: RuntimeException) {
            cached ?: throw ex
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetch(): DiscoveryDocument {
        val body = requireNotNull(http.get("auth-social-oidc-discovery", url, Map::class.java) { loggingTag("auth-social.oidc.discovery") }.block()) { "empty discovery document" } as Map<String, Any?>
        fun str(name: String) = (body[name] as? String)?.takeIf { it.isNotBlank() }
        val docIssuer = str("issuer") ?: throw IllegalStateException("discovery document has no issuer")
        check(docIssuer == issuer) { "discovery document issuer '$docIssuer' differs from the configured issuer '$issuer'" }
        val doc = DiscoveryDocument(
            issuer = docIssuer,
            authorizationEndpoint = str("authorization_endpoint") ?: throw IllegalStateException("discovery document has no authorization_endpoint"),
            tokenEndpoint = str("token_endpoint") ?: throw IllegalStateException("discovery document has no token_endpoint"),
            userinfoEndpoint = str("userinfo_endpoint"),
            jwksUri = str("jwks_uri"),
        )
        requireSecureUrl("discovered authorization_endpoint", doc.authorizationEndpoint)
        requireSecureUrl("discovered token_endpoint", doc.tokenEndpoint)
        doc.userinfoEndpoint?.let { requireSecureUrl("discovered userinfo_endpoint", it) }
        doc.jwksUri?.let { requireSecureUrl("discovered jwks_uri", it) }
        return doc
    }
}
