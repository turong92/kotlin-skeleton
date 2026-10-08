package dev.sumin.skeleton.auth.social.google

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthAuthorizeInfo
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthTokenErrorLog
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import org.springframework.http.HttpHeaders

class GoogleOAuthProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: AuthSocialProperties.Provider,
) : OAuthProvider {
    override val providerId: String = PROVIDER_ID

    init {
        require(properties.clientId.isNotBlank()) { "Google OAuth clientId must not be blank" }
        require(properties.clientSecret.isNotBlank()) { "Google OAuth clientSecret must not be blank" }
    }

    /** Google 은 웹 서버 앱에서도 `code_verifier` 를 받는다 — 있으면 보낸다 (client secret 은 그대로 필요). 근거: docs/modules/auth-social-google.md (확인 필요 표시) */
    override val pkce: PkceMode = PkceMode.SUPPORTED
    override val publicClientId: String get() = properties.clientId
    override val publicRedirectUri: String? get() = properties.redirectUri
    override val authorize = OAuthAuthorizeInfo(
        url = "https://accounts.google.com/o/oauth2/v2/auth",
        scopes = listOf("openid", "email", "profile"),
        params = mapOf("response_type" to "code"),
    )

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
        fetchProfile(OAuthCodeExchange(authorizationCode, redirectUri))

    override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
        val token = exchangeToken(exchange.authorizationCode, exchange.redirectUri, exchange.codeVerifier)
        val profile = requireNotNull(
            httpClient.get(
                clientName = PROFILE_CLIENT_NAME,
                path = properties.profilePath ?: DEFAULT_PROFILE_PATH,
                responseType = GoogleUserInfoResponse::class.java,
            ) {
                baseUrl(properties.profileBaseUrl ?: properties.apiBaseUrl ?: DEFAULT_PROFILE_BASE_URL)
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token.accessToken}")
                loggingTag("auth-social.google.profile")
            }.block(),
        )

        val verified = profile.emailVerified == true
        val authoritative = verified && when (properties.emailTrust ?: AuthSocialProperties.EmailTrust.AUTHORITATIVE) {
            AuthSocialProperties.EmailTrust.VERIFIED_CLAIM -> true
            AuthSocialProperties.EmailTrust.AUTHORITATIVE -> GoogleEmailAuthority.authoritative(profile.email, hostedDomain(token, profile))
        }

        return OAuthUserProfile(
            provider = providerId,
            providerUserId = profile.sub,
            email = profile.email,
            username = profile.email ?: profile.sub,
            displayName = profile.name,
            emailVerified = verified,
            emailAuthoritative = authoritative,
            avatarUrl = profile.picture?.takeIf { it.startsWith("https://") },
        )
    }

    /**
     * `hd`(Workspace 호스팅 도메인)는 userinfo 에 보장되지 않고 ID 토큰에 있다. ID 토큰은 토큰 엔드포인트(TLS, 우리 client secret 으로 직접 호출)에서 바로 받은 것이라
     * 서명 검증 없이 읽어도 된다(OIDC Core 3.1.3.7 · Google 안내) — 그래도 userinfo 와 `sub` · `email` 이 맞는 토큰만 믿는다.
     */
    private fun hostedDomain(token: GoogleTokenResponse, profile: GoogleUserInfoResponse): String? {
        val claims = GoogleIdToken.claims(token.idToken) ?: return null
        if (claims["sub"] != profile.sub) return null
        val email = claims["email"]
        if (email != null && !email.equals(profile.email, ignoreCase = true)) return null
        return claims["hd"]
    }

    private fun exchangeToken(
        authorizationCode: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): GoogleTokenResponse =
        try {
            requireNotNull(
                httpClient.postForm(
                    clientName = TOKEN_CLIENT_NAME,
                    path = properties.tokenPath ?: DEFAULT_TOKEN_PATH,
                    form = tokenForm(authorizationCode, redirectUri, codeVerifier),
                    responseType = GoogleTokenResponse::class.java,
                ) {
                    baseUrl(properties.tokenBaseUrl ?: DEFAULT_TOKEN_BASE_URL)
                    loggingTag("auth-social.google.token")
                }.block(),
            )
        } catch (ex: ExternalHttpStatusException) {
            if (ex.upstreamStatus in 400..499) {
                OAuthTokenErrorLog.rejected(providerId, ex.upstreamStatus ?: 0, ex.upstreamBody)
                throw OAuthInvalidAuthorizationCodeException(providerId)
            }
            throw ex
        }

    private fun tokenForm(
        authorizationCode: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): Map<String, String> =
        linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to properties.clientId,
            "client_secret" to properties.clientSecret,
            "code" to authorizationCode,
        ).apply {
            val effectiveRedirectUri = redirectUri ?: properties.redirectUri
            if (!effectiveRedirectUri.isNullOrBlank()) {
                put("redirect_uri", effectiveRedirectUri)
            }
            if (!codeVerifier.isNullOrBlank()) put("code_verifier", codeVerifier)
        }

    data class GoogleTokenResponse(
        @JsonProperty("access_token")
        val accessToken: String,
        @JsonProperty("id_token")
        val idToken: String? = null,
    )

    data class GoogleUserInfoResponse(
        val sub: String,
        val email: String? = null,
        @JsonProperty("email_verified")
        val emailVerified: Boolean? = null,
        val name: String? = null,
        val picture: String? = null,
    )

    private companion object {
        const val PROVIDER_ID = "google"
        const val TOKEN_CLIENT_NAME = "auth-social-google-token"
        const val PROFILE_CLIENT_NAME = "auth-social-google-profile"
        const val DEFAULT_TOKEN_BASE_URL = "https://oauth2.googleapis.com"
        const val DEFAULT_TOKEN_PATH = "/token"
        const val DEFAULT_PROFILE_BASE_URL = "https://openidconnect.googleapis.com"
        const val DEFAULT_PROFILE_PATH = "/v1/userinfo"
    }
}
