package dev.sumin.skeleton.auth.social.naver

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(AuthSocialProperties::class)
class NaverOAuthProviderAutoConfiguration {
    @Bean("naverOAuthProvider")
    @ConditionalOnProperty(
        prefix = "skeleton.auth-social.providers.naver",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(name = ["naverOAuthProvider"])
    fun naverOAuthProvider(
        httpClient: ExternalHttpClient,
        properties: AuthSocialProperties,
    ): NaverOAuthProvider =
        NaverOAuthProvider(
            httpClient = httpClient,
            properties = properties.providers["naver"] ?: AuthSocialProperties.Provider(enabled = true),
        )
}
