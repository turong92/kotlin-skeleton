package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * `skeleton.account.login-id.enabled=true` 일 때 아이디 없는 계정의 보호된 요청을 막는 인터셉터 — 이메일 게이트와 마찬가지로 **계정 HTTP(`skeleton.account.http.enabled`)와 따로** 등록된다
 * (앱이 자기 컨트롤러를 두려고 계정 컨트롤러를 꺼도 `me.loginId` 와 게이트는 `login-id.enabled` 만 본다). 꺼짐(기본)이면 아무것도 등록되지 않는다.
 * 순서: 아이디(-110) → 이메일(-100, [AccountEmailRequiredAutoConfiguration]) → `legal` 의 재동의(0).
 */
@AutoConfiguration(after = [AccountAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.account.login-id", name = ["enabled"], havingValue = "true")
class AccountLoginIdRequiredAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["accountLoginIdRequiredConfigurer"])
    fun accountLoginIdRequiredConfigurer(properties: AccountProperties, accounts: ObjectProvider<AccountRepository>): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(LoginIdRequiredInterceptor(properties.loginId) { accounts.getObject() }).order(LOGIN_ID_REQUIRED_ORDER)
            }
        }

    companion object {
        /** 이메일 게이트(-100)보다 먼저 — 아이디도 이메일도 없는 계정은 `LOGIN_ID_REQUIRED` 가 먼저 나간다 */
        const val LOGIN_ID_REQUIRED_ORDER = -110
    }
}
