package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.time.Clock

/**
 * 설정 → 제공자. **시작할 때 모든 것을 검증한다** (켜진 제공자만): issuer 도 엔드포인트도 없음 · client secret 없음 · http 엔드포인트 ·
 * discovery 실패 · discovery 의 issuer 불일치. 메시지에 제공자 코드 · 설정 키 · 고치는 법을 담는다 — 제공자를 꺼 두면(client-id 비움) 아무 검사도 네트워크도 없다.
 */
object OidcProviders {
    fun create(code: String, provider: OidcProperties.Provider, global: OidcProperties, http: ExternalHttpClient, clock: Clock = Clock.systemUTC()): OidcOAuthProvider {
        val cfg = OidcPresets.resolve(code, provider)
        val key = "skeleton.auth-social-oidc.providers.${cfg.code}"
        check(cfg.clientId.isNotBlank()) { "$key.client-id must not be blank" }
        check(cfg.clientAuth == OidcProperties.ClientAuth.NONE || cfg.clientSecret.isNotBlank()) { "$key.client-secret must not be blank (or set token-endpoint-auth=none for a public client)" }
        val explicit = cfg.authorizationEndpoint != null && cfg.tokenEndpoint != null && (cfg.jwksUri != null || cfg.algorithms.all { it.name.startsWith("HS") })
        check(cfg.issuer != null || explicit) {
            "$key needs either an issuer (OpenID discovery) or the explicit authorization-endpoint, token-endpoint and jwks-uri"
        }
        listOf("authorization-endpoint" to cfg.authorizationEndpoint, "token-endpoint" to cfg.tokenEndpoint, "userinfo-endpoint" to cfg.userinfoEndpoint, "jwks-uri" to cfg.jwksUri)
            .forEach { (name, url) -> url?.let { requireSecureUrl("$key.$name", it) } }
        cfg.issuer?.let { requireSecureUrl("$key.issuer", it) }

        val discovery = cfg.issuer?.let { OidcDiscovery(http, it, global.discoveryCacheTtl, clock) }
        if (!explicit) {
            // 켜진 제공자의 discovery 가 안 되면 기동 실패 — 첫 로그인 때 500 으로 알게 되는 것보다 낫다
            try {
                discovery!!.document()
            } catch (ex: RuntimeException) {
                throw IllegalStateException(
                    "OIDC discovery failed for provider '${cfg.code}' (issuer ${cfg.issuer}, ${discovery!!.url}): ${ex.message}. " +
                        "Fix $key.issuer, or set the explicit authorization-endpoint, token-endpoint and jwks-uri, or remove $key.client-id to disable the provider.",
                    ex,
                )
            }
        }
        val jwks = if (cfg.algorithms.any { !it.name.startsWith("HS") }) {
            JwksCache(http, { cfg.jwksUri ?: discovery!!.document().jwksUri ?: error("provider '${cfg.code}' publishes no jwks_uri") }, global.jwksCacheTtl, global.jwksRefreshCooldown, clock)
        } else {
            null
        }
        val verifier = IdTokenVerifier(cfg.clientId, cfg.clientSecret, cfg.algorithms, { cfg.issuer }, jwks, global.clockSkew, clock)
        return OidcOAuthProvider(cfg, http, discovery, verifier)
    }
}
