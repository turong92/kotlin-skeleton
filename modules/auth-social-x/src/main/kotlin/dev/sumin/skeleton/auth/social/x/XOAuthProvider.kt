package dev.sumin.skeleton.auth.social.x

import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthAuthorizeInfo
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthTokenErrors
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import java.util.Base64
import org.springframework.http.HttpHeaders

/** X API 의 한도 창이 찼다 (429). [resetEpochSeconds] 는 `x-rate-limit-reset` — 창이 풀리는 시각 (없으면 null) */
class XRateLimitedException(val resetEpochSeconds: Long?, context: ExternalHttpErrorContext) : ExternalHttpException(
    message = "X API rate limit exceeded" + (resetEpochSeconds?.let { " (window resets at epoch second $it)" } ?: ""),
    clientName = context.clientName,
    method = context.method,
    uri = context.uri,
    upstreamStatus = 429,
    retryable = true,
    errorCode = PlatformErrorCode.EXTERNAL_SERVICE_ERROR,
)

/**
 * X OAuth 2.0 (Authorization Code + PKCE, 기밀 클라이언트).
 * 근거: https://docs.x.com/resources/fundamentals/authentication/oauth-2-0/authorization-code · https://docs.x.com/x-api/users/get-my-user
 * 인가 코드는 받은 뒤 **30초** 안에 교환해야 한다(문서) — 프론트는 콜백을 받자마자 백엔드를 불러야 한다.
 */
class XOAuthProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: XProperties,
) : OAuthProvider {
    override val providerId: String = "x"
    override val pkce: PkceMode = PkceMode.REQUIRED
    override val nonce: NonceMode = NonceMode.UNSUPPORTED
    override val autoEnabled: Boolean = true
    override val publicClientId: String get() = properties.clientId
    override val publicRedirectUri: String? get() = properties.redirectUri

    private val scopes: List<String> = listOf("users.read", "tweet.read") + if (properties.requestEmail) listOf("users.email") else emptyList()

    override val authorize = OAuthAuthorizeInfo(properties.authorizeUrl, scopes, mapOf("response_type" to "code"))

    init {
        require(properties.clientId.isNotBlank()) { "skeleton.auth-social-x.client-id must not be blank" }
        require(properties.clientSecret.isNotBlank()) { "skeleton.auth-social-x.client-secret must not be blank (X apps are confidential clients: the token endpoint uses HTTP Basic)" }
    }

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
        fetchProfile(OAuthCodeExchange(authorizationCode, redirectUri))

    override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
        val accessToken = exchangeToken(exchange)
        val fields = if (properties.requestEmail) "profile_image_url,confirmed_email" else "profile_image_url"
        @Suppress("UNCHECKED_CAST")
        val body = try {
            requireNotNull(
                httpClient.get("auth-social-x-me", properties.apiBaseUrl.trimEnd('/') + "/2/users/me", Map::class.java) {
                    queryParam("user.fields", fields)
                    header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                    loggingTag("auth-social.x.me")
                    errorMapper { context ->
                        if (context.upstreamStatus == 429) {
                            XRateLimitedException(context.upstreamHeaders.getFirst("x-rate-limit-reset")?.toLongOrNull(), context)
                        } else {
                            DefaultExternalHttpErrorMapper().map(context)
                        }
                    }
                }.block(),
            ) as Map<String, Any?>
        } catch (ex: ExternalHttpStatusException) {
            throw IllegalStateException("X API GET /2/users/me failed: HTTP ${ex.upstreamStatus} ${describe(ex.upstreamBody)}", ex)
        }
        val data = body["data"] as? Map<*, *> ?: throw IllegalStateException("X API GET /2/users/me returned no data object: ${body.keys}")
        fun text(name: String) = (data[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val id = text("id") ?: throw IllegalStateException("X API GET /2/users/me returned no user id")
        val email = if (properties.requestEmail) text("confirmed_email") else null
        return OAuthUserProfile(
            provider = providerId,
            providerUserId = id,
            email = email,
            username = text("username"),
            displayName = text("name"),
            emailVerified = email != null && properties.trustConfirmedEmail,
            avatarUrl = text("profile_image_url")?.takeIf { it.startsWith("https://") },
        )
    }

    private fun exchangeToken(exchange: OAuthCodeExchange): String {
        val form = linkedMapOf("grant_type" to "authorization_code", "code" to exchange.authorizationCode)
        (exchange.redirectUri ?: properties.redirectUri)?.takeIf { it.isNotBlank() }?.let { form["redirect_uri"] = it }
        exchange.codeVerifier?.let { form["code_verifier"] = it }
        val basic = Base64.getEncoder().encodeToString("${java.net.URLEncoder.encode(properties.clientId, Charsets.UTF_8)}:${java.net.URLEncoder.encode(properties.clientSecret, Charsets.UTF_8)}".toByteArray())
        return try {
            val response = requireNotNull(
                httpClient.postForm("auth-social-x-token", properties.apiBaseUrl.trimEnd('/') + "/2/oauth2/token", form, Map::class.java) {
                    header(HttpHeaders.AUTHORIZATION, "Basic $basic")
                    loggingTag("auth-social.x.token")
                }.block(),
            )
            response["access_token"] as? String ?: throw IllegalStateException("X token response has no access_token")
        } catch (ex: ExternalHttpStatusException) {
            throw OAuthTokenErrors.map(providerId, ex, verifierSent = exchange.codeVerifier != null)
        }
    }

    /** X 의 문제 본문(`title` · `detail` · `reason`)을 한 줄로 — 티어 · 권한 문제가 로그에서 바로 읽히게 */
    private fun describe(body: String): String {
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        return listOfNotNull(field("title"), field("reason")?.let { "reason=$it" }, field("detail")).joinToString(" - ").ifEmpty { body.take(200) }
    }
}
