package dev.sumin.skeleton.auth.social.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.auth-social")
data class AuthSocialProperties(
    val providers: Map<String, Provider> = emptyMap(),
) {
    data class Provider(
        val enabled: Boolean = false,
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String? = null,
        val apiBaseUrl: String? = null,
        val tokenBaseUrl: String? = null,
        val profileBaseUrl: String? = null,
        val tokenPath: String? = null,
        val profilePath: String? = null,
    )
}
