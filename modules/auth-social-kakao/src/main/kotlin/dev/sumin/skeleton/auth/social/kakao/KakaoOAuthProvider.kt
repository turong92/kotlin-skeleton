package dev.sumin.skeleton.auth.social.kakao

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
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
        val profile = requireNotNull(
            httpClient.get(
                clientName = PROFILE_CLIENT_NAME,
                path = properties.profilePath ?: DEFAULT_PROFILE_PATH,
                responseType = KakaoUserInfoResponse::class.java,
            ) {
                baseUrl(properties.profileBaseUrl ?: properties.apiBaseUrl ?: DEFAULT_PROFILE_BASE_URL)
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token.accessToken}")
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

    data class KakaoUserInfoResponse(
        val id: Long,
        @JsonProperty("kakao_account")
        val kakaoAccount: KakaoAccount? = null,
    )

    data class KakaoAccount(
        val email: String? = null,
        val profile: KakaoProfile? = null,
    )

    data class KakaoProfile(
        val nickname: String? = null,
    )

    private companion object {
        const val PROVIDER_ID = "kakao"
        const val TOKEN_CLIENT_NAME = "auth-social-kakao-token"
        const val PROFILE_CLIENT_NAME = "auth-social-kakao-profile"
        const val DEFAULT_TOKEN_BASE_URL = "https://kauth.kakao.com"
        const val DEFAULT_TOKEN_PATH = "/oauth/token"
        const val DEFAULT_PROFILE_BASE_URL = "https://kapi.kakao.com"
        const val DEFAULT_PROFILE_PATH = "/v2/user/me"
    }
}
