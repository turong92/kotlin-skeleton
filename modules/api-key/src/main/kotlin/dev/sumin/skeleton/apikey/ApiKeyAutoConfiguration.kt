package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.apikey.erasure.ApiKeyAccountErasureListener
import dev.sumin.skeleton.common.apikey.ApiKeyScopeDeclaration
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 키 발급 · 소유자 정책 · 탈퇴 고리(요청 인증은 문과 함께 `ApiKeyWebAutoConfiguration` 에 있다). 저장소([ApiKeyStore])는 `api-key-jdbc` 가 내놓는다 - **없으면 기동에 실패한다**(메모리 구현은 없다).
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
        events: ObjectProvider<AccountEventPublisher>,
    ): ApiKeys = ApiKeys(
        store, scopes, { policies.orderedStream().toList() }, properties, time.getIfAvailable { TimeProvider.systemUtc() }, tokens,
        events = events.getIfAvailable { AccountEventPublisher.NONE },
    )

    @Bean
    @ConditionalOnMissingBean(name = ["apiKeyPermissionDeclaration"])
    fun apiKeyPermissionDeclaration(): PermissionDeclaration = ApiKeyPermissions.declaration

    @Bean
    @ConditionalOnMissingBean(name = ["apiKeyAccountErasureListener"])
    fun apiKeyAccountErasureListener(store: ApiKeyStore): AccountErasureListener = ApiKeyAccountErasureListener(store)
}
