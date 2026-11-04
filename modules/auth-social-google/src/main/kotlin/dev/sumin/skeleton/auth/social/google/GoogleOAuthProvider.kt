package dev.sumin.skeleton.auth.social.google

import com.fasterxml.jackson.annotation.JsonProperty
import com.nimbusds.jose.JWSAlgorithm
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.idtoken.IdTokenRejected
import dev.sumin.skeleton.auth.social.idtoken.IdTokenVerifier
import dev.sumin.skeleton.auth.social.idtoken.JwksCache
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthIdTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthAuthorizeInfo
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthTokenErrorLog
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders

class GoogleOAuthProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: AuthSocialProperties.Provider,
    clock: Clock = Clock.systemUTC(),
) : OAuthProvider {
    private val log = LoggerFactory.getLogger(GoogleOAuthProvider::class.java)
    override val providerId: String = PROVIDER_ID

    init {
        require(properties.clientId.isNotBlank()) { "Google OAuth clientId must not be blank" }
        require(properties.clientSecret.isNotBlank()) { "Google OAuth clientSecret must not be blank" }
    }

    /** Google 은 웹 서버 앱에서도 `code_verifier` 를 받는다 — 있으면 보낸다 (client secret 은 그대로 필요). 근거: docs/modules/auth-social-google.md (확인 필요 표시) */
    override val pkce: PkceMode = PkceMode.SUPPORTED
    /** 앱 SDK 의 ID 토큰을 받는다. nonce 는 요청에 있으면 토큰과 비교한다 — 구글 SDK 는 로그인마다 새 nonce 를 줄 수 없어 앱이 안 보낼 수 있다 */
    override val nativeToken: NativeTokenKind = NativeTokenKind.ID_TOKEN
    override val nonce: NonceMode = NonceMode.SUPPORTED

    /** 키는 구글의 JWKS(`api-base-url` 로 덮는다), `aud` 는 웹 client id 하나 — 앱이 `serverClientId` 를 웹 client id 로 초기화하면 안드로이드 · iOS 토큰이 모두 이 aud 로 온다 */
    private val verifier = IdTokenVerifier(
        clientId = properties.clientId, clientSecret = "", algorithms = listOf(JWSAlgorithm.RS256), issuers = { ISSUERS },
        jwks = JwksCache(httpClient, { (properties.apiBaseUrl ?: DEFAULT_JWKS_BASE_URL) + JWKS_PATH }, Duration.ofHours(1), Duration.ofMinutes(5), clock),
        skew = Duration.ofSeconds(60), clock = clock,
    )

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

        return profileOf(profile.sub, profile.email, profile.emailVerified == true, profile.name, profile.picture, hostedDomain(token, profile))
    }

    override fun verifyNativeToken(token: OAuthNativeToken): OAuthUserProfile {
        val claims = try {
            verifier.verify(token.token, token.nonce)
        } catch (ex: IdTokenRejected) {
            log.warn("ID token of provider '{}' rejected: {}", providerId, ex.reason)
            throw OAuthIdTokenInvalidException(providerId, ex.reason)
        }
        val sub = claims.subject?.takeIf { it.isNotBlank() } ?: throw OAuthIdTokenInvalidException(providerId, "sub claim is missing")
        val verified = claims.getClaim("email_verified").let { it == true || (it is String && it.equals("true", ignoreCase = true)) }
        // 서명을 검증한 토큰이라 `hd` 를 바로 믿는다
        return profileOf(sub, claims.getStringClaim("email"), verified, claims.getStringClaim("name"), claims.getStringClaim("picture"), claims.getStringClaim("hd"))
    }

    private fun profileOf(sub: String, email: String?, verified: Boolean, name: String?, picture: String?, hostedDomain: String?): OAuthUserProfile {
        val authoritative = verified && when (properties.emailTrust ?: AuthSocialProperties.EmailTrust.AUTHORITATIVE) {
            AuthSocialProperties.EmailTrust.VERIFIED_CLAIM -> true
            AuthSocialProperties.EmailTrust.AUTHORITATIVE -> GoogleEmailAuthority.authoritative(email, hostedDomain)
        }
        return OAuthUserProfile(
            provider = providerId,
            providerUserId = sub,
            email = email,
            username = email ?: sub,
            displayName = name,
            emailVerified = verified,
            emailAuthoritative = authoritative,
            avatarUrl = picture?.takeIf { it.startsWith("https://") },
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
        val ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        const val DEFAULT_JWKS_BASE_URL = "https://www.googleapis.com"
        const val JWKS_PATH = "/oauth2/v3/certs"
        const val TOKEN_CLIENT_NAME = "auth-social-google-token"
        const val PROFILE_CLIENT_NAME = "auth-social-google-profile"
        const val DEFAULT_TOKEN_BASE_URL = "https://oauth2.googleapis.com"
        const val DEFAULT_TOKEN_PATH = "/token"
        const val DEFAULT_PROFILE_BASE_URL = "https://openidconnect.googleapis.com"
        const val DEFAULT_PROFILE_PATH = "/v1/userinfo"
    }
}
