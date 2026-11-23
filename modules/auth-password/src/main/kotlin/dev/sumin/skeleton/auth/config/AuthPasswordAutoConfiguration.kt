package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.api.PasswordLoginController
import dev.sumin.skeleton.auth.login.LoginHooks
import dev.sumin.skeleton.auth.login.PasswordLoginService
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 비밀번호 로그인 `POST /api/v1/auth/login` — `auth` 의 토큰 발급 · 계정 저장소 위에 얹는다. 설정 묶음은 따로 없다.
 * [PasswordEncoder] 는 `auth` 의 기본(BCrypt) 이거나 앱 · `account-password` 가 낸 것을 그대로 쓴다.
 * 로그인 앞뒤 고리([LoginHooks])는 `auth` 에 있고, 한도 · 기록을 거는 `account-password` 가 구현한다 — 서로를 모른다.
 */
@AutoConfiguration(after = [AuthAutoConfiguration::class])
class AuthPasswordAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun passwordLoginService(
        accountRepository: AuthAccountRepository,
        passwordEncoder: PasswordEncoder,
        authTokenResponseFactory: AuthTokenResponseFactory,
        hooks: ObjectProvider<LoginHooks>,
    ): PasswordLoginService =
        PasswordLoginService(accountRepository, passwordEncoder, authTokenResponseFactory) { hooks.orderedStream().toList() }

    @Bean
    @ConditionalOnMissingBean
    fun passwordLoginController(loginService: PasswordLoginService, clientIps: ObjectProvider<ClientIps>): PasswordLoginController =
        PasswordLoginController(loginService, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean(name = ["authPasswordPublicEndpointContributor"])
    fun authPasswordPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/auth/login") }
}
