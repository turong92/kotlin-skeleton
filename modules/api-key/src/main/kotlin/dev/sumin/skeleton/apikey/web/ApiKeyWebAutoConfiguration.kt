package dev.sumin.skeleton.apikey.web

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.Reauth
import dev.sumin.skeleton.apikey.ApiKeyAccessInterceptor
import dev.sumin.skeleton.apikey.ApiKeyAutoConfiguration
import dev.sumin.skeleton.apikey.ApiKeyProperties
import dev.sumin.skeleton.apikey.ApiKeyAuthenticator
import dev.sumin.skeleton.apikey.ApiKeyStore
import dev.sumin.skeleton.apikey.ApiKeyTokens
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.security.BearerTokenAuthenticator
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.apikey.ApiKeys
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 서블릿 웹 앱이고 Spring Security(호출자)가 있을 때만. **접근 인터셉터는 사용자 경로 스위치와 상관없이** 늘 건다 - 경로를 닫아도 키가 닿는 곳을 정하는 문은 열려 있어야 한다.
 * 사용자 경로는 `skeleton.api-key.http.enabled=false` 로 끄고, 운영자 경로는 `skeleton.api-key.admin.enabled=true` 일 때만 켜진다(꺼져 있으면 404).
 */
@AutoConfiguration(after = [ApiKeyAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class ApiKeyWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(ApiKeys::class)
    @ConditionalOnProperty(prefix = "skeleton.api-key.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun apiKeyController(keys: ApiKeys, reauth: Reauth, accounts: AccountRepository, time: TimeProvider, properties: ApiKeyProperties): ApiKeyController =
        ApiKeyController(keys, reauth, accounts, time, properties.http.basePath)

    @Bean
    @ConditionalOnBean(ApiKeys::class)
    @ConditionalOnProperty(prefix = "skeleton.api-key.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun apiKeyAdminController(
        keys: ApiKeys,
        access: OperatorAccess,
        audit: OperatorAuditLog,
        time: TimeProvider,
        labels: ObjectProvider<AccountLabels>,
    ): ApiKeyAdminController = ApiKeyAdminController(keys, access, audit, time, labels.getIfAvailable())

    @Bean
    @ConditionalOnBean(ApiKeys::class)
    @ConditionalOnMissingBean(name = ["apiKeyAuthenticator"])
    fun apiKeyAuthenticator(
        store: ApiKeyStore,
        tokens: ApiKeyTokens,
        accounts: AuthAccountRepository,
        time: ObjectProvider<TimeProvider>,
        clientIps: ObjectProvider<ClientIps>,
    ): BearerTokenAuthenticator =
        ApiKeyAuthenticator(store, tokens, accounts, time.getIfAvailable { TimeProvider.systemUtc() }, clientIps.getIfAvailable { ClientIps() })

    /** 인증기와 같은 자동설정에 있어 함께 서고 함께 빠진다(문 없이 키만 받는 일이 없다 - 교체 지점이 아니다). 다른 문(이메일 필수 -100 · 아이디 -110 · 재동의 0)보다 먼저 - 키가 닿을 수 없는 곳이면 그 문들의 답을 보기 전에 막는다 */
    @Bean
    @ConditionalOnBean(ApiKeys::class)
    fun apiKeyAccessConfigurer(properties: ApiKeyProperties, limits: ObjectProvider<RateLimitStore>, time: TimeProvider): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(ApiKeyAccessInterceptor(properties, { limits.getIfAvailable { FALLBACK_LIMITS } }, time)).order(ACCESS_ORDER)
            }
        }

    companion object {
        const val ACCESS_ORDER = -200
        private val FALLBACK_LIMITS = InMemoryFixedWindowRateLimitStore()
    }
}
