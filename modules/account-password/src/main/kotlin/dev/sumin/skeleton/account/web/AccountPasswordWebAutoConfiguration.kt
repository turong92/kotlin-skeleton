package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.PasswordService
import dev.sumin.skeleton.account.RegistrationService
import dev.sumin.skeleton.account.password.AccountPasswordAutoConfiguration
import dev.sumin.skeleton.account.password.PasswordPolicy
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 비밀번호 HTTP — `account` 의 HTTP 와 같은 조건(서블릿 웹 앱 · Spring Security · `skeleton.account.http.enabled`)에서만 있다.
 * 공개 경로(가입 · 확인 · 찾기 · 재설정 · 정책)는 [PublicEndpointContributor] 로 연다.
 */
@AutoConfiguration(after = [AccountPasswordAutoConfiguration::class, AccountWebAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.account.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AccountPasswordWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun accountPasswordPublicController(
        registration: RegistrationService,
        passwords: PasswordService,
        policy: PasswordPolicy,
        signIns: AccountSignInService,
        clientIps: ObjectProvider<ClientIps>,
        tokens: ObjectProvider<dev.sumin.skeleton.auth.api.AuthTokenResponseFactory>,
    ): AccountPasswordPublicController =
        AccountPasswordPublicController(registration, passwords, policy, signIns, clientIps.getIfAvailable { ClientIps() }) { tokens.getObject() }

    @Bean
    @ConditionalOnMissingBean
    fun accountPasswordController(callers: AccountCallers, passwords: PasswordService): AccountPasswordController = AccountPasswordController(callers, passwords)

    @Bean
    @ConditionalOnMissingBean(name = ["accountPasswordPublicEndpointContributor"])
    fun accountPasswordPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("POST", "/api/v1/account/sign-up")
            registry.add("POST", "/api/v1/account/verification/resend")
            registry.add("POST", "/api/v1/auth/verify-email")
            registry.add("POST", "/api/v1/account/password/forgot")
            registry.add("POST", "/api/v1/account/password/reset")
            registry.add("GET", "/api/v1/account/password/policy")
        }
}
