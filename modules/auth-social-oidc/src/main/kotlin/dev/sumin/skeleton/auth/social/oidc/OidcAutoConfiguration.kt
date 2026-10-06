package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.time.Clock
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotationMetadata

/**
 * `skeleton.auth-social-oidc.providers.<코드>` 마다 `client-id` 가 있으면 [OAuthProvider] 빈 하나를 등록한다 (코드는 맵 키라서 개수가 설정에 달렸다 — 그래서 레지스트라).
 * `client-id` 가 비면 그 항목은 건너뛴다. 같은 이름의 빈이 이미 있으면 그것을 쓴다 (앱이 한 제공자만 교체할 수 있다).
 */
@AutoConfiguration
@EnableConfigurationProperties(OidcProperties::class)
@Import(OidcProviderRegistrar::class)
class OidcAutoConfiguration

class OidcProviderRegistrar : ImportBeanDefinitionRegistrar, EnvironmentAware {
    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        val props = Binder.get(environment).bind("skeleton.auth-social-oidc", OidcProperties::class.java).orElseGet { OidcProperties() }!!
        props.providers.filterValues { it.clientId.isNotBlank() }.forEach { (rawCode, provider) ->
            val code = rawCode.trim().lowercase()
            val beanName = "oidcOAuthProvider.$code"
            if (registry.containsBeanDefinition(beanName)) return@forEach
            val factory = registry as BeanFactory
            registry.registerBeanDefinition(
                beanName,
                BeanDefinitionBuilder.genericBeanDefinition(OidcOAuthProvider::class.java) {
                    val clock = factory.getBeanProvider(Clock::class.java).getIfAvailable { Clock.systemUTC() }
                    OidcProviders.create(code, provider, props, factory.getBean(ExternalHttpClient::class.java), clock)
                }.beanDefinition,
            )
        }
    }
}
