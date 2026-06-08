package dev.sumin.skeleton.auth.social.google

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(AuthSocialProperties::class)
class GoogleOAuthProviderAutoConfiguration {
    @Bean("googleOAuthProvider")
    @ConditionalOnProperty(
        prefix = "skeleton.auth-social.providers.google",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(name = ["googleOAuthProvider"])
    fun googleOAuthProvider(
        httpClient: ExternalHttpClient,
        properties: AuthSocialProperties,
    ): GoogleOAuthProvider =
        GoogleOAuthProvider(
            httpClient = httpClient,
            properties = properties.providers["google"] ?: AuthSocialProperties.Provider(enabled = true),
        )
}
