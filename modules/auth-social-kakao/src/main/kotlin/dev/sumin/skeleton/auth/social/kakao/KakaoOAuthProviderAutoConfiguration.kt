package dev.sumin.skeleton.auth.social.kakao

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(AuthSocialProperties::class)
class KakaoOAuthProviderAutoConfiguration {
    @Bean("kakaoOAuthProvider")
    @ConditionalOnProperty(
        prefix = "skeleton.auth-social.providers.kakao",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(name = ["kakaoOAuthProvider"])
    fun kakaoOAuthProvider(
        httpClient: ExternalHttpClient,
        properties: AuthSocialProperties,
    ): KakaoOAuthProvider =
        KakaoOAuthProvider(
            httpClient = httpClient,
            properties = properties.providers["kakao"] ?: AuthSocialProperties.Provider(enabled = true),
        )
}
