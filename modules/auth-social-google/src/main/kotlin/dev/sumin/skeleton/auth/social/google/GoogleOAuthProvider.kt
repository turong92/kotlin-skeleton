package dev.sumin.skeleton.auth.social.google

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
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

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
        val token = exchangeToken(authorizationCode, redirectUri)
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

        return OAuthUserProfile(
            provider = providerId,
            providerUserId = profile.sub,
            email = profile.email,
            username = profile.email ?: profile.sub,
            displayName = profile.name,
        )
    }

    private fun exchangeToken(
        authorizationCode: String,
        redirectUri: String?,
    ): GoogleTokenResponse =
        try {
            requireNotNull(
                httpClient.postForm(
                    clientName = TOKEN_CLIENT_NAME,
                    path = properties.tokenPath ?: DEFAULT_TOKEN_PATH,
                    form = tokenForm(authorizationCode, redirectUri),
                    responseType = GoogleTokenResponse::class.java,
                ) {
                    baseUrl(properties.tokenBaseUrl ?: DEFAULT_TOKEN_BASE_URL)
                    loggingTag("auth-social.google.token")
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

    data class GoogleTokenResponse(
        @JsonProperty("access_token")
        val accessToken: String,
    )

    data class GoogleUserInfoResponse(
        val sub: String,
        val email: String? = null,
        val name: String? = null,
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
