package dev.sumin.skeleton.auth.magiclink

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.MagicLinkIssuer
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** 매직 링크 로그인 — `account` 위에 수단 하나(`magic_link`)와 엔드포인트 둘을 얹는다. 스키마 · 계정 모듈 변경 없음. `skeleton.auth-magic-link.http.enabled=false` 로 엔드포인트를 끈다 */
@AutoConfiguration(after = [AccountAutoConfiguration::class])
@EnableConfigurationProperties(MagicLinkProperties::class)
class MagicLinkAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["magicLinkSignInMethod"])
    fun magicLinkSignInMethod(): SignInMethod = MagicLinkSignInMethod()

    @Bean
    @ConditionalOnMissingBean
    fun magicLinkService(core: AccountCore, signIn: AccountSignInService, properties: MagicLinkProperties): MagicLinkService = MagicLinkService(core, signIn, properties)

    /** `account` 의 "이미 계정이 있어요" 메일에 1회용 로그인 링크 한 줄을 더하는 고리 — 이 모듈이 있을 때만 있다 (`account` 는 이 모듈에 의존하지 않는다) */
    @Bean
    @ConditionalOnMissingBean(MagicLinkIssuer::class)
    fun magicLinkIssuer(service: MagicLinkService): MagicLinkIssuer = MagicLinkIssuer { service.issueFor(it) }
}

@AutoConfiguration(after = [MagicLinkAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.auth-magic-link.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class MagicLinkWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun magicLinkController(service: MagicLinkService, tokens: AuthTokenResponseFactory, clientIps: ObjectProvider<ClientIps>): MagicLinkController =
        MagicLinkController(service, tokens, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean(name = ["magicLinkPublicEndpointContributor"])
    fun magicLinkPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("POST", "/api/v1/auth/magic-link/request")
            registry.add("POST", "/api/v1/auth/magic-link/redeem")
        }
}
