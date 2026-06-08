package dev.sumin.skeleton.auth.social.naver

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
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
        require(properties.clientSecret.isNotBlank()) { "Naver OAuth clientSecret must not be blank" }
    }

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
        val token = exchangeToken(authorizationCode, redirectUri)
        val profile = requireNotNull(
            httpClient.get(
                clientName = PROFILE_CLIENT_NAME,
                path = properties.profilePath ?: DEFAULT_PROFILE_PATH,
                responseType = NaverUserInfoResponse::class.java,
            ) {
                baseUrl(properties.profileBaseUrl ?: properties.apiBaseUrl ?: DEFAULT_PROFILE_BASE_URL)
                header(HttpHeaders.AUTHORIZATION, "Bearer ${token.accessToken}")
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
    ): NaverTokenResponse =
        try {
            requireNotNull(
                httpClient.postForm(
                    clientName = TOKEN_CLIENT_NAME,
                    path = properties.tokenPath ?: DEFAULT_TOKEN_PATH,
                    form = tokenForm(authorizationCode, redirectUri),
                    responseType = NaverTokenResponse::class.java,
                ) {
                    baseUrl(properties.tokenBaseUrl ?: DEFAULT_TOKEN_BASE_URL)
                    loggingTag("auth-social.naver.token")
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
