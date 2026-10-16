package dev.sumin.skeleton.auth.social.kakao

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

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
    @Conditional(ProjectsNotConfigured::class)
    fun kakaoOAuthProvider(
        httpClient: ExternalHttpClient,
        properties: AuthSocialProperties,
    ): KakaoOAuthProvider =
        KakaoOAuthProvider(
            httpClient = httpClient,
            properties = properties.providers["kakao"] ?: AuthSocialProperties.Provider(enabled = true),
        )

    /** 프로젝트 모드: 켜짐 · 키는 프로젝트마다 (`skeleton.projects.<id>.config.auth-social.providers.kakao.*`) — 위 빈 대신 */
    @Bean
    @Conditional(ProjectsConfigured::class)
    @ConditionalOnMissingBean(name = ["kakaoOAuthProviderSource"])
    fun kakaoOAuthProviderSource(httpClient: ExternalHttpClient, projects: ProjectRegistry): OAuthProviderSource =
        OAuthProviderSource.ofProvidersKey(projects, "kakao") { KakaoOAuthProvider(httpClient, it) }
}
