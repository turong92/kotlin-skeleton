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
 * `skeleton.account.email.required=true` 일 때 주소 없는 계정의 보호된 요청을 막는 인터셉터 — **계정 HTTP(`skeleton.account.http.enabled`)와 따로** 등록된다:
 * 앱이 자기 컨트롤러를 두려고 계정 컨트롤러를 꺼도, `me.emailRequired` · 첫 등록의 재인증 면제는 `required` 만 보므로 403 도 같이 있어야 한다(꺼져 있으면 "필수" 가 말뿐이다).
 * `legal` 의 재동의 인터셉터(순서 0)보다 먼저 돈다. 아이디 게이트([AccountLoginIdRequiredAutoConfiguration], -110)는 이것보다도 먼저다.
 */
@AutoConfiguration(after = [AccountAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.account.email", name = ["required"], havingValue = "true")
class AccountEmailRequiredAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["accountEmailRequiredConfigurer"])
    fun accountEmailRequiredConfigurer(properties: AccountProperties, accounts: ObjectProvider<AccountRepository>): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(EmailRequiredInterceptor(properties.email, properties.loginId.enabled) { accounts.getObject() }).order(EMAIL_REQUIRED_ORDER)
            }
        }

    companion object {
        /** `legal` 의 재동의 인터셉터는 기본 순서(0) — 주소 확인이 먼저다 */
        const val EMAIL_REQUIRED_ORDER = -100
    }
}
