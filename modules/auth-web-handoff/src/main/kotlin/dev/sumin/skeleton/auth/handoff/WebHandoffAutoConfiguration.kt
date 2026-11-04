package dev.sumin.skeleton.auth.handoff

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.session.SessionEventListener
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** 앱 -> 웹 로그인 넘기기 - `skeleton.auth-web-handoff.enabled=true` 일 때만. `account` 의 한 번 쓰는 토큰 장치 위에 얹고 스키마 · 계정 모듈 변경은 없다 */
@AutoConfiguration(after = [AccountAutoConfiguration::class])
@EnableConfigurationProperties(WebHandoffProperties::class)
@ConditionalOnProperty(prefix = "skeleton.auth-web-handoff", name = ["enabled"], havingValue = "true")
class WebHandoffAutoConfiguration {
    /** 계정의 세션이 한꺼번에 닫히면(세션 사건) 남은 코드도 닫는다 */
    @Bean
    @ConditionalOnMissingBean
    fun webHandoff(core: AccountCore, accounts: AuthAccountRepository, properties: WebHandoffProperties): WebHandoff = WebHandoff(core.tokens, accounts, properties)

    @Bean
    @ConditionalOnMissingBean(name = ["webHandoffSessionEventListener"])
    fun webHandoffSessionEventListener(handoff: WebHandoff): SessionEventListener = WebHandoffSessionListener(handoff)
}

@AutoConfiguration(after = [WebHandoffAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.auth-web-handoff", name = ["enabled"], havingValue = "true")
class WebHandoffWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun webHandoffController(handoff: WebHandoff, tokens: AuthTokenResponseFactory): WebHandoffController = WebHandoffController(handoff, tokens)

    @Bean
    @ConditionalOnMissingBean(name = ["webHandoffPublicEndpointContributor"])
    fun webHandoffPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/auth/web-handoff/redeem") }
}
