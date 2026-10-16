package dev.sumin.skeleton.auth.social.x

import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

@AutoConfiguration
@EnableConfigurationProperties(XProperties::class)
class XAutoConfiguration {
    /** client id 가 있을 때만 — 빈 문자열은 "설정 안 함" 이다 (환경변수 자리만 만들어 둔 yml 이 아무것도 바꾸지 않는다) */
    @Bean("xOAuthProvider")
    @ConditionalOnExpression("!'\${skeleton.auth-social-x.client-id:}'.trim().isEmpty()")
    @ConditionalOnMissingBean(name = ["xOAuthProvider"])
    @Conditional(ProjectsNotConfigured::class)
    fun xOAuthProvider(httpClient: ExternalHttpClient, properties: XProperties): XOAuthProvider = XOAuthProvider(httpClient, properties)

    /** 프로젝트 모드: 프로젝트의 `auth-social-x.client-id` 가 있을 때만 그 프로젝트에 X 가 있다 (`skeleton.projects.<id>.config.auth-social-x.*`) — 위 빈 대신 */
    @Bean
    @Conditional(ProjectsConfigured::class)
    @ConditionalOnMissingBean(name = ["xOAuthProviderSource"])
    fun xOAuthProviderSource(httpClient: ExternalHttpClient, projects: ProjectRegistry): OAuthProviderSource =
        OAuthProviderSource { project ->
            projects.bind(project, "skeleton.auth-social-x", XProperties::class.java)
                .takeIf { it.clientId.isNotBlank() }
                ?.let { listOf(XOAuthProvider(httpClient, it)) }
                ?: emptyList()
        }
}
