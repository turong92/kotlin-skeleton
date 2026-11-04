package dev.sumin.skeleton.auth.social.kakao

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.OAuthAccessTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderNotFoundException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthTokenErrorLog
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpStatusException
import org.springframework.http.HttpHeaders

class KakaoOAuthProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: AuthSocialProperties.Provider,
) : OAuthProvider {
    override val providerId: String = PROVIDER_ID

    init {
        require(properties.clientId.isNotBlank()) { "Kakao OAuth clientId must not be blank" }
        require(properties.clientSecret.isNotBlank()) { "Kakao OAuth clientSecret must not be blank" }
    }

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
        val token = exchangeToken(authorizationCode, redirectUri)
        return profileOf(token.accessToken)
    }

    /** `app-id` 가 있을 때만 앱 SDK 액세스 토큰 입구가 열린다 */
    override val nativeToken: NativeTokenKind? get() = if (properties.appId.isNullOrBlank()) null else NativeTokenKind.ACCESS_TOKEN

    override fun verifyNativeToken(token: OAuthNativeToken): OAuthUserProfile {
        val expectedAppId = properties.appId?.trim()?.takeIf { it.isNotEmpty() } ?: throw OAuthProviderNotFoundException(providerId)
        val info = try {
            requireNotNull(
                httpClient.get(
                    clientName = TOKEN_INFO_CLIENT_NAME,
                    path = TOKEN_INFO_PATH,
                    responseType = KakaoTokenInfoResponse::class.java,
                ) {
                    baseUrl(properties.profileBaseUrl ?: properties.apiBaseUrl ?: DEFAULT_PROFILE_BASE_URL)
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${token.token}")
                    loggingTag("auth-social.kakao.token-info")
                }.block(),
            )
        } catch (ex: ExternalHttpStatusException) {
            if (ex.upstreamStatus == 401) throw OAuthAccessTokenInvalidException(providerId, "kakao rejected the access token")
            throw ex
        }
        // 다른 앱이 발급한 토큰이면 (그 앱의 사용자가 우리 앱으로 로그인하려는 것) 거절
        if (info.appId?.toString() != expectedAppId) throw OAuthAccessTokenInvalidException(providerId, "access token belongs to another app")
        return try {
            profileOf(token.token)
        } catch (ex: ExternalHttpStatusException) {
            if (ex.upstreamStatus == 401) throw OAuthAccessTokenInvalidException(providerId, "kakao rejected the access token")
            throw ex
        }
    }

    private fun profileOf(accessToken: String): OAuthUserProfile {
        val profile = requireNotNull(
            httpClient.get(
                clientName = PROFILE_CLIENT_NAME,
                path = properties.profilePath ?: DEFAULT_PROFILE_PATH,
                responseType = KakaoUserInfoResponse::class.java,
            ) {
                baseUrl(properties.profileBaseUrl ?: properties.apiBaseUrl ?: DEFAULT_PROFILE_BASE_URL)
                header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                loggingTag("auth-social.kakao.profile")
            }.block(),
        )
        val providerUserId = profile.id.toString()
        val email = profile.kakaoAccount?.email
        val nickname = profile.kakaoAccount?.profile?.nickname

        return OAuthUserProfile(
            provider = providerId,
            providerUserId = providerUserId,
            email = email,
            username = email ?: providerUserId,
            displayName = nickname,
            // Kakao: 다른 카카오계정으로 넘어가 만료된 주소는 is_email_verified=true · is_email_valid=false 로 온다 — 옛 보유자의 "확인됨" 을 믿지 않는다
            emailVerified = profile.kakaoAccount?.emailValid == true && profile.kakaoAccount.emailVerified == true,
        )
    }

    private fun exchangeToken(
        authorizationCode: String,
        redirectUri: String?,
    ): KakaoTokenResponse =
        try {
            requireNotNull(
                httpClient.postForm(
                    clientName = TOKEN_CLIENT_NAME,
                    path = properties.tokenPath ?: DEFAULT_TOKEN_PATH,
                    form = tokenForm(authorizationCode, redirectUri),
                    responseType = KakaoTokenResponse::class.java,
                ) {
                    baseUrl(properties.tokenBaseUrl ?: DEFAULT_TOKEN_BASE_URL)
                    loggingTag("auth-social.kakao.token")
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
        }

    data class KakaoTokenResponse(
        @JsonProperty("access_token")
        val accessToken: String,
    )

    /** `GET /v1/user/access_token_info` — 이 토큰이 어느 앱의 것인가 */
    data class KakaoTokenInfoResponse(
        @JsonProperty("app_id")
        val appId: Long? = null,
    )

    data class KakaoUserInfoResponse(
        val id: Long,
        @JsonProperty("kakao_account")
        val kakaoAccount: KakaoAccount? = null,
    )

    data class KakaoAccount(
        val email: String? = null,
        @JsonProperty("is_email_valid")
        val emailValid: Boolean? = null,
        @JsonProperty("is_email_verified")
        val emailVerified: Boolean? = null,
        val profile: KakaoProfile? = null,
    )

    data class KakaoProfile(
        val nickname: String? = null,
    )

    private companion object {
        const val PROVIDER_ID = "kakao"
        const val TOKEN_CLIENT_NAME = "auth-social-kakao-token"
        const val PROFILE_CLIENT_NAME = "auth-social-kakao-profile"
        const val TOKEN_INFO_CLIENT_NAME = "auth-social-kakao-token-info"
        const val TOKEN_INFO_PATH = "/v1/user/access_token_info"
        const val DEFAULT_TOKEN_BASE_URL = "https://kauth.kakao.com"
        const val DEFAULT_TOKEN_PATH = "/oauth/token"
        const val DEFAULT_PROFILE_BASE_URL = "https://kapi.kakao.com"
        const val DEFAULT_PROFILE_PATH = "/v2/user/me"
    }
}
