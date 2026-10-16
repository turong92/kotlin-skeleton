package dev.sumin.skeleton.captcha.turnstile

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(afterName = ["dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration"])
@ConditionalOnProperty(prefix = "skeleton.captcha-turnstile", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(ExternalHttpClient::class)
@EnableConfigurationProperties(TurnstileProperties::class)
class TurnstileAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun turnstileVerifier(http: ExternalHttpClient, properties: TurnstileProperties, projects: ObjectProvider<ProjectRegistry>): TurnstileVerifier {
        projects.getIfAvailable()?.let { registry ->
            // 프로젝트 모드: 켜짐은 앱 전체 하나지만 비밀 키 · 기대 hostname · action 은 프로젝트마다 (공통 값 위에 덮어). 켜 두면 서비스 중인 프로젝트 모두 비밀 키가 있어야 한다
            val perProject = registry.scopedFrom("skeleton.captcha-turnstile", TurnstileProperties::class.java) { project, p ->
                require(p.secretKey.isNotBlank()) { "skeleton.projects.${project.id}.config.captcha-turnstile.secret-key must be set for project '${project.id}' when captcha is enabled." }
                p
            }
            registry.active.forEach { perProject.of(it) }
            return TurnstileVerifier(http) { perProject.get() }
        }
        require(properties.secretKey.isNotBlank()) { "skeleton.captcha-turnstile.secret-key must be set when enabled." }
        return TurnstileVerifier(http, properties)
    }
}
