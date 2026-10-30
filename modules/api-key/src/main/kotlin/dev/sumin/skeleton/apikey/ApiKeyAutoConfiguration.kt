package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.apikey.erasure.ApiKeyAccountErasureListener
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.security.BearerTokenAuthenticator
import dev.sumin.skeleton.common.apikey.ApiKeyScopeDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 키 발급 · 요청 인증 · 소유자 정책 · 탈퇴 고리. 저장소([ApiKeyStore])는 `api-key-jdbc` 가 내놓는다 - **없으면 기동에 실패한다**(메모리 구현은 없다).
 * 요청 인증은 `auth` 의 [BearerTokenAuthenticator] 고리에 꽂힌다 - `JwtAuthenticationFilter` 가 접두사로 JWT 와 가른다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.apikey.jdbc.ApiKeyJdbcAutoConfiguration"])
@EnableConfigurationProperties(ApiKeyProperties::class)
class ApiKeyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun apiKeyTokens(properties: ApiKeyProperties): ApiKeyTokens = ApiKeyTokens(properties.tokenPrefix)

    @Bean
    @ConditionalOnMissingBean
    fun apiKeyScopes(declarations: ObjectProvider<ApiKeyScopeDeclaration>): ApiKeyScopes = ApiKeyScopes { declarations.orderedStream().toList() }

    @Bean
    @ConditionalOnMissingBean(name = ["accountApiKeyOwnerPolicy"])
    fun accountApiKeyOwnerPolicy(): ApiKeyOwnerPolicy = AccountApiKeyOwnerPolicy()

    @Bean
    @ConditionalOnMissingBean
    fun apiKeys(
        store: ApiKeyStore,
        scopes: ApiKeyScopes,
        policies: ObjectProvider<ApiKeyOwnerPolicy>,
        properties: ApiKeyProperties,
        time: ObjectProvider<TimeProvider>,
        tokens: ApiKeyTokens,
    ): ApiKeys = ApiKeys(store, scopes, { policies.orderedStream().toList() }, properties, time.getIfAvailable { TimeProvider.systemUtc() }, tokens)

    @Bean
    @ConditionalOnMissingBean(name = ["apiKeyAuthenticator"])
    fun apiKeyAuthenticator(
        store: ApiKeyStore,
        tokens: ApiKeyTokens,
        accounts: AuthAccountRepository,
        time: ObjectProvider<TimeProvider>,
        clientIps: ObjectProvider<ClientIps>,
    ): BearerTokenAuthenticator =
        ApiKeyAuthenticator(store, tokens, accounts, time.getIfAvailable { TimeProvider.systemUtc() }, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean(name = ["apiKeyAccountErasureListener"])
    fun apiKeyAccountErasureListener(store: ApiKeyStore): AccountErasureListener = ApiKeyAccountErasureListener(store)
}
