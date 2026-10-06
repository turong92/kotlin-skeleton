package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthAuthorizeInfo
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthIdTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthTokenErrors
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import java.util.Base64
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders

/**
 * 설정 한 덩어리([OidcResolved])로 만들어지는 범용 OIDC 제공자. 인가 코드 교환 → ID 토큰 검증 → (필요하면) userinfo → [OAuthUserProfile].
 * 한 모듈 인스턴스가 이런 제공자를 여러 개 낸다 ([OidcAutoConfiguration]).
 */
class OidcOAuthProvider(
    private val config: OidcResolved,
    private val http: ExternalHttpClient,
    private val discovery: OidcDiscovery?,
    private val verifier: IdTokenVerifier,
) : OAuthProvider {
    private val log = LoggerFactory.getLogger(OidcOAuthProvider::class.java)

    override val providerId: String = config.code
    override val pkce: PkceMode = config.pkce
    override val nonce: NonceMode = config.nonce
    override val autoEnabled: Boolean = true
    override val publicClientId: String = config.clientId
    override val publicRedirectUri: String? = config.redirectUri

    override val authorize: OAuthAuthorizeInfo?
        get() {
            val url = config.authorizationEndpoint ?: runCatching { discovery?.document()?.authorizationEndpoint }.getOrNull() ?: return null
            return OAuthAuthorizeInfo(url, config.scopes, mapOf("response_type" to "code") + config.authorizeParams)
        }

    private val tokenUrl: String get() = config.tokenEndpoint ?: discovery!!.document().tokenEndpoint
    private val userinfoUrl: String? get() = config.userinfoEndpoint ?: discovery?.document()?.userinfoEndpoint

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
        fetchProfile(OAuthCodeExchange(authorizationCode, redirectUri))

    override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
        val token = exchangeToken(exchange)
        val idToken = token["id_token"] as? String
        val accessToken = token["access_token"] as? String
        val claims: MutableMap<String, Any?> = if (idToken != null) {
            try {
                verifier.verify(idToken, exchange.nonce).claims.toMutableMap()
            } catch (ex: IdTokenRejected) {
                log.warn("ID token of provider '{}' rejected: {}", providerId, ex.reason)
                throw OAuthIdTokenInvalidException(providerId, ex.reason)
            }
        } else {
            check(config.userinfo != OidcProperties.UserinfoMode.NEVER) { "OIDC provider '$providerId' returned no id_token (is 'openid' in scopes?) and userinfo is NEVER" }
            mutableMapOf()
        }
        if (needsUserinfo(idToken != null, claims)) {
            val info = userinfo(accessToken ?: error("OIDC provider '$providerId' returned no access_token for the userinfo call"))
            val idSub = claims[config.claims.subject]
            val infoSub = info[config.claims.subject]
            if (idToken != null && idSub != infoSub) {
                log.warn("userinfo subject of provider '{}' differs from the ID token subject", providerId)
                throw OAuthIdTokenInvalidException(providerId, "userinfo sub does not match the ID token sub")
            }
            info.forEach { (k, v) -> claims.putIfAbsent(k, v) }
        }
        return profileOf(claims)
    }

    private fun needsUserinfo(hasIdToken: Boolean, claims: Map<String, Any?>): Boolean = when (config.userinfo) {
        OidcProperties.UserinfoMode.NEVER -> false
        OidcProperties.UserinfoMode.ALWAYS -> true
        OidcProperties.UserinfoMode.FALLBACK -> !hasIdToken || claims[config.claims.email] == null || claims[config.claims.name] == null
    } && userinfoUrl != null

    private fun profileOf(claims: Map<String, Any?>): OAuthUserProfile {
        fun text(name: String) = (claims[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val sub = text(config.claims.subject) ?: throw OAuthIdTokenInvalidException(providerId, "${config.claims.subject} claim is missing")
        val email = text(config.claims.email)
        val verified = email != null && when (config.emailTrust) {
            OidcProperties.EmailTrust.NEVER -> false
            OidcProperties.EmailTrust.ALWAYS -> true
            OidcProperties.EmailTrust.CLAIM -> claims[config.claims.emailVerified].let { it == true || (it is String && it.equals("true", ignoreCase = true)) }
        }
        return OAuthUserProfile(
            provider = providerId,
            providerUserId = sub,
            email = email,
            username = text("preferred_username") ?: email ?: sub,
            displayName = text(config.claims.name),
            emailVerified = verified,
            avatarUrl = text(config.claims.picture)?.takeIf { it.startsWith("https://") },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun exchangeToken(exchange: OAuthCodeExchange): Map<String, Any?> {
        val form = linkedMapOf("grant_type" to "authorization_code", "code" to exchange.authorizationCode)
        (exchange.redirectUri ?: config.redirectUri)?.takeIf { it.isNotBlank() }?.let { form["redirect_uri"] = it }
        exchange.codeVerifier?.let { form["code_verifier"] = it }
        if (config.clientAuth != OidcProperties.ClientAuth.BASIC) form["client_id"] = config.clientId
        if (config.clientAuth == OidcProperties.ClientAuth.POST) form["client_secret"] = config.clientSecret
        return try {
            requireNotNull(
                http.postForm("auth-social-oidc-token", tokenUrl, form, Map::class.java) {
                    loggingTag("auth-social.oidc.token")
                    if (config.clientAuth == OidcProperties.ClientAuth.BASIC) {
                        header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString("${formEncode(config.clientId)}:${formEncode(config.clientSecret)}".toByteArray()))
                    }
                }.block(),
            ) as Map<String, Any?>
        } catch (ex: ExternalHttpStatusException) {
            throw OAuthTokenErrors.map(providerId, ex, verifierSent = exchange.codeVerifier != null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun userinfo(accessToken: String): Map<String, Any?> =
        requireNotNull(
            http.get("auth-social-oidc-userinfo", userinfoUrl!!, Map::class.java) {
                loggingTag("auth-social.oidc.userinfo")
                header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            }.block(),
        ) as Map<String, Any?>

    /** RFC 6749 §2.3.1: Basic 의 id · secret 은 먼저 `application/x-www-form-urlencoded` 로 인코딩한다 */
    private fun formEncode(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8)
}
