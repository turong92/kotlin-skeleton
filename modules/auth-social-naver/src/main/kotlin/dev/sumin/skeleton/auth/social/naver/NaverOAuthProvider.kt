package dev.sumin.skeleton.auth.social.naver

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.OAuthAccessTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthTokenErrorLog
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import org.springframework.http.HttpHeaders

class NaverOAuthProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: AuthSocialProperties.Provider,
) : OAuthProvider {
    override val providerId: String = PROVIDER_ID

    init {
        require(properties.clientId.isNotBlank()) { "Naver OAuth clientId must not be blank" }
    }

    /** client secret 은 인가 코드 교환에만 쓴다 — 비어 있으면(앱 전용 백엔드) 액세스 토큰 검증만 열리고 코드 흐름은 없다 */
    override val codeFlow: Boolean get() = properties.clientSecret.isNotBlank()

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
        fetchProfile(OAuthCodeExchange(authorizationCode, redirectUri))

    /** Naver 의 토큰 발급 요청은 `state` 를 받는다 — 콜백이 받은 값이 있으면 싣는다 (없으면 예전처럼 싣지 않는다) */
    override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
        val token = exchangeToken(exchange.authorizationCode, exchange.redirectUri, exchange.state)
        return profileOf(token.accessToken)
    }

    override val nativeToken: NativeTokenKind get() = NativeTokenKind.ACCESS_TOKEN

    /** 앱 SDK 의 액세스 토큰을 프로필 API 로 확인한다. 네이버는 어느 앱이 발급한 토큰인지 확인할 방법이 없다 */
    override fun verifyNativeToken(token: OAuthNativeToken): OAuthUserProfile =
        try {
            profileOf(token.token)
        } catch (ex: ExternalHttpStatusException) {
            if (ex.upstreamStatus == 401) throw OAuthAccessTokenInvalidException(providerId, "naver rejected the access token")
            throw ex
        }

    private fun profileOf(accessToken: String): OAuthUserProfile {
        val profile = requireNotNull(
            httpClient.get(
                clientName = PROFILE_CLIENT_NAME,
                path = properties.profilePath ?: DEFAULT_PROFILE_PATH,
                responseType = NaverUserInfoResponse::class.java,
            ) {
                baseUrl(properties.profileBaseUrl ?: properties.apiBaseUrl ?: DEFAULT_PROFILE_BASE_URL)
                header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                loggingTag("auth-social.naver.profile")
            }.block(),
        )
        val response = profile.response

        return OAuthUserProfile(
            provider = providerId,
            providerUserId = response.id,
            email = response.email,
            username = response.email ?: response.id,
            displayName = response.name ?: response.nickname,
        )
    }

    private fun exchangeToken(
        authorizationCode: String,
        redirectUri: String?,
        state: String?,
    ): NaverTokenResponse =
        try {
            requireNotNull(
                httpClient.postForm(
                    clientName = TOKEN_CLIENT_NAME,
                    path = properties.tokenPath ?: DEFAULT_TOKEN_PATH,
                    form = tokenForm(authorizationCode, redirectUri, state),
                    responseType = NaverTokenResponse::class.java,
                ) {
                    baseUrl(properties.tokenBaseUrl ?: DEFAULT_TOKEN_BASE_URL)
                    loggingTag("auth-social.naver.token")
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
        state: String?,
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
            if (!state.isNullOrBlank()) put("state", state)
        }

    data class NaverTokenResponse(
        @JsonProperty("access_token")
        val accessToken: String,
    )

    data class NaverUserInfoResponse(
        val response: NaverProfile,
    )

    data class NaverProfile(
        val id: String,
        val email: String? = null,
        val nickname: String? = null,
        val name: String? = null,
    )

    private companion object {
        const val PROVIDER_ID = "naver"
        const val TOKEN_CLIENT_NAME = "auth-social-naver-token"
        const val PROFILE_CLIENT_NAME = "auth-social-naver-profile"
        const val DEFAULT_TOKEN_BASE_URL = "https://nid.naver.com"
        const val DEFAULT_TOKEN_PATH = "/oauth2.0/token"
        const val DEFAULT_PROFILE_BASE_URL = "https://openapi.naver.com"
        const val DEFAULT_PROFILE_PATH = "/v1/nid/me"
    }
}
