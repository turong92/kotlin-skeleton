package dev.sumin.skeleton.auth.social.apple

import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

@AutoConfiguration
@EnableConfigurationProperties(AppleProperties::class)
class AppleAutoConfiguration {
    /** client id 가 있을 때만 — 빈 문자열은 "설정 안 함" 이다 (환경변수 자리만 만들어 둔 yml 이 아무것도 바꾸지 않는다) */
    @Bean("appleOAuthProvider")
    @ConditionalOnExpression("!'\${skeleton.auth-social-apple.client-id:}'.trim().isEmpty()")
    @ConditionalOnMissingBean(name = ["appleOAuthProvider"])
    @Conditional(ProjectsNotConfigured::class)
    fun appleOAuthProvider(httpClient: ExternalHttpClient, properties: AppleProperties, clock: ObjectProvider<Clock>): AppleOAuthProvider =
        AppleOAuthProvider(httpClient, properties, clock.getIfAvailable { Clock.systemUTC() })

    /** 프로젝트 모드: 프로젝트의 `auth-social-apple.client-id` 가 있을 때만 그 프로젝트에 애플이 있다 (`skeleton.projects.<id>.config.auth-social-apple.*`) — 위 빈 대신 */
    @Bean
    @Conditional(ProjectsConfigured::class)
    @ConditionalOnMissingBean(name = ["appleOAuthProviderSource"])
    fun appleOAuthProviderSource(httpClient: ExternalHttpClient, projects: ProjectRegistry, clock: ObjectProvider<Clock>): OAuthProviderSource =
        OAuthProviderSource { project ->
            // 번들 id · team · key · 개인키는 프로젝트 자기 값만 (공통 값으로 내려가지 않는다 — 다른 프로젝트의 애플 앱·키로 로그인이 시작되면 안 된다)
            val own = projects.bindOwn(project, "skeleton.auth-social-apple", AppleProperties::class.java)
            projects.bind(project, "skeleton.auth-social-apple", AppleProperties::class.java)
                .copy(clientId = own.clientId, teamId = own.teamId, keyId = own.keyId, privateKey = own.privateKey)
                .takeIf { it.clientId.isNotBlank() }
                ?.let { listOf(AppleOAuthProvider(httpClient, it, clock.getIfAvailable { Clock.systemUTC() })) }
                ?: emptyList()
        }
}

/** 탈퇴 철회 경로 — 애플이 켜진 앱에서만 (한 서비스: client-id 가 있을 때 / 프로젝트 모드: 켜진 프로젝트가 어디서 철회하는지는 요청의 프로젝트가 정하므로 경로는 항상 있고, 안 켠 프로젝트는 404) */
@AutoConfiguration(after = [AppleAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AppleWebAutoConfiguration {
    @Bean
    @ConditionalOnExpression("!'\${skeleton.auth-social-apple.client-id:}'.trim().isEmpty()")
    @ConditionalOnMissingBean
    @Conditional(ProjectsNotConfigured::class)
    fun appleRevokeController(providers: OAuthProviderRegistry): AppleRevokeController = AppleRevokeController(providers)

    @Bean
    @Conditional(ProjectsConfigured::class)
    @ConditionalOnMissingBean(AppleRevokeController::class)
    fun appleRevokeControllerForProjects(providers: OAuthProviderRegistry): AppleRevokeController = AppleRevokeController(providers)
}
