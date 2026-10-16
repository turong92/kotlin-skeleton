package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotationMetadata

/**
 * `skeleton.auth-social-oidc.providers.<코드>` 마다 `client-id` 가 있으면 [OAuthProvider] 빈 하나를 등록한다 (코드는 맵 키라서 개수가 설정에 달렸다 — 그래서 레지스트라).
 * `client-id` 가 비면 그 항목은 건너뛴다. 같은 이름의 빈이 이미 있으면 그것을 쓴다 (앱이 한 제공자만 교체할 수 있다).
 */
/** 설정 한 덩어리 → 제공자. 앱이 같은 타입의 빈을 두면 그것이 쓰인다 (예: 다른 HTTP 클라이언트 · 추가 검증) */
fun interface OidcProviderFactory {
    fun create(code: String, provider: OidcProperties.Provider, global: OidcProperties, http: ExternalHttpClient, clock: Clock): OidcOAuthProvider
}

@AutoConfiguration
@EnableConfigurationProperties(OidcProperties::class)
@Import(OidcProviderRegistrar::class)
class OidcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun oidcProviderFactory(): OidcProviderFactory = OidcProviderFactory { code, provider, global, http, clock -> OidcProviders.create(code, provider, global, http, clock) }

    /** 제공자 빈 정의(아래 레지스트라)가 팩터리 메서드로 부르는 곳 — 설정 덩어리는 정의에 담지 않고 여기서 코드로 찾는다 (Spring AOT 가 람다 공급자 달린 정의를 못 굽는다, docs/native-image.md) */
    @Bean
    @ConditionalOnMissingBean
    fun oidcProviderBuilder(properties: OidcProperties, factory: OidcProviderFactory, http: ObjectProvider<ExternalHttpClient>, clock: ObjectProvider<Clock>): OidcProviderBuilder =
        OidcProviderBuilder(properties, factory, http, clock)

    /**
     * 프로젝트 모드: 프로젝트마다 자기 `auth-social-oidc.providers.*` (공통 위에 덮어)로 제공자를 만든다 — 위 레지스트라의 빈 대신.
     * discovery · JWKS 보관은 제공자 인스턴스 안이라 프로젝트끼리 섞이지 않는다. 잘못된 설정은 기동 실패(레지스트리가 프로젝트 id 를 붙인다)
     */
    @Bean
    @Conditional(ProjectsConfigured::class)
    @ConditionalOnMissingBean(name = ["oidcOAuthProviderSource"])
    fun oidcOAuthProviderSource(projects: ProjectRegistry, factory: OidcProviderFactory, http: ObjectProvider<ExternalHttpClient>, clock: ObjectProvider<Clock>): OAuthProviderSource =
        OAuthProviderSource { project ->
            // 제공자 목록(`providers.*`)은 프로젝트 자기 값만 — 공통에 남은 제공자가 모든 프로젝트에 켜지지 않는다. 캐시 시간 같은 나머지는 공통 위에 덮는다
            val props = projects.bind(project, "skeleton.auth-social-oidc", OidcProperties::class.java)
                .copy(providers = projects.bindOwn(project, "skeleton.auth-social-oidc", OidcProperties::class.java).providers)
            props.providers.filterValues { it.clientId.isNotBlank() }
                .map { (code, provider) -> factory.create(code.trim().lowercase(), provider, props, http.getObject(), clock.getIfAvailable { Clock.systemUTC() }) }
        }
}

class OidcProviderBuilder(
    private val properties: OidcProperties,
    private val factory: OidcProviderFactory,
    private val http: ObjectProvider<ExternalHttpClient>,
    private val clock: ObjectProvider<Clock>,
) {
    fun create(code: String): OidcOAuthProvider {
        val provider = properties.providers.entries.first { it.key.trim().lowercase() == code }.value
        return factory.create(code, provider, properties, http.getObject(), clock.getIfAvailable { Clock.systemUTC() })
    }
}

class OidcProviderRegistrar : ImportBeanDefinitionRegistrar, EnvironmentAware {
    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        if (ProjectRegistry.configured(environment)) return   // 프로젝트 모드는 oidcOAuthProviderSource 가 프로젝트마다 만든다
        val props = Binder.get(environment).bind("skeleton.auth-social-oidc", OidcProperties::class.java).orElseGet { OidcProperties() }!!
        props.providers.filterValues { it.clientId.isNotBlank() }.forEach { (rawCode, provider) ->
            val code = rawCode.trim().lowercase()
            val beanName = "oidcOAuthProvider.$code"
            if (registry.containsBeanDefinition(beanName)) return@forEach
            // 팩터리 메서드 + 코드(문자열) 하나만 정의에 담는다 — 람다 공급자는 Spring AOT(네이티브 이미지)가 굽지 못한다. 설정 덩어리는 oidcProviderBuilder 가 실행 때 코드로 찾는다
            registry.registerBeanDefinition(
                beanName,
                BeanDefinitionBuilder.genericBeanDefinition(OidcOAuthProvider::class.java)
                    .setFactoryMethodOnBean("create", "oidcProviderBuilder")
                    .addConstructorArgValue(code)
                    .beanDefinition,
            )
        }
    }
}
