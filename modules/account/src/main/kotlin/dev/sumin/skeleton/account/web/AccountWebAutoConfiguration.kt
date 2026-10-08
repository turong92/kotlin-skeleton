package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AdminService
import dev.sumin.skeleton.account.DeletionService
import dev.sumin.skeleton.account.EmailChangeService
import dev.sumin.skeleton.account.PasswordService
import dev.sumin.skeleton.account.ProfileService
import dev.sumin.skeleton.account.Reauth
import dev.sumin.skeleton.account.RegistrationService
import dev.sumin.skeleton.account.password.PasswordPolicy
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
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
 * 계정 HTTP — 서블릿 웹 앱이고 Spring Security 가 있을 때만. `skeleton.account.http.enabled=false` 로 끈다(앱이 자기 컨트롤러를 둘 때).
 * 인증은 앱의 보안 설정이 건다 (`auth` 의 기본 체인은 모든 경로에 인증을 요구하고, 가입 · 확인 · 재설정 경로는 [PublicEndpointContributor] 로 연다).
 * 관리자 경로는 `skeleton.account.admin.enabled=true` 일 때만 등록된다.
 */
@AutoConfiguration(after = [AccountAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.account.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AccountWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun accountCallers(properties: AccountProperties, accounts: ObjectProvider<AccountRepository>): AccountCallers = AccountCallers(properties.admin) { accounts.getObject() }

    @Bean
    @ConditionalOnMissingBean
    fun accountPublicController(
        registration: RegistrationService,
        passwords: PasswordService,
        policy: PasswordPolicy,
        deletion: DeletionService,
        clientIps: ObjectProvider<ClientIps>,
        tokens: ObjectProvider<dev.sumin.skeleton.auth.api.AuthTokenResponseFactory>,
    ): AccountPublicController = AccountPublicController(registration, passwords, policy, deletion, clientIps.getIfAvailable { ClientIps() }) { tokens.getObject() }

    @Bean
    @ConditionalOnMissingBean
    fun accountController(
        callers: AccountCallers,
        profile: ProfileService,
        passwords: PasswordService,
        emailChange: EmailChangeService,
        identities: IdentityService,
        deletion: DeletionService,
        reauth: Reauth,
        clientIps: ObjectProvider<ClientIps>,
    ): AccountController = AccountController(callers, profile, passwords, emailChange, identities, deletion, reauth, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.account.contact-email", name = ["enabled"], havingValue = "true")
    fun contactEmailController(callers: AccountCallers, contact: dev.sumin.skeleton.account.ContactEmailService, clientIps: ObjectProvider<ClientIps>): ContactEmailController =
        ContactEmailController(callers, contact, clientIps.getIfAvailable { ClientIps() })

    /** `skeleton.account.email.required=true` 일 때만 — 주소 없는 계정의 보호된 요청을 막는다. `legal` 의 재동의 필터(순서 0)보다 먼저 돈다 */
    @Bean
    @ConditionalOnMissingBean(name = ["accountEmailRequiredConfigurer"])
    @ConditionalOnProperty(prefix = "skeleton.account.email", name = ["required"], havingValue = "true")
    fun accountEmailRequiredConfigurer(properties: AccountProperties, accounts: ObjectProvider<AccountRepository>): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(EmailRequiredInterceptor(properties.email) { accounts.getObject() }).order(EMAIL_REQUIRED_ORDER)
            }
        }

    @Bean
    @ConditionalOnMissingBean
    fun authMethodsController(
        registry: dev.sumin.skeleton.account.signin.SignInMethodRegistry,
        properties: AccountProperties,
        captcha: dev.sumin.skeleton.account.abuse.CaptchaGate,
        social: ObjectProvider<SocialMethodsSource>,
        delivery: ObjectProvider<dev.sumin.skeleton.auth.session.RefreshDeliveryInfo>,
    ): AuthMethodsController = AuthMethodsController(
        registry, properties, captcha,
        { social.getIfAvailable()?.enabled().orEmpty() },
        { delivery.getIfAvailable()?.mode },
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.account.admin", name = ["enabled"], havingValue = "true")
    fun adminAccountController(callers: AccountCallers, admin: AdminService, purge: dev.sumin.skeleton.account.AccountPurgeService): AdminAccountController = AdminAccountController(callers, admin, purge)

    @Bean
    @ConditionalOnMissingBean(name = ["accountPublicEndpointContributor"])
    fun accountPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("POST", "/api/v1/account/sign-up")
            registry.add("POST", "/api/v1/account/verification/resend")
            registry.add("POST", "/api/v1/auth/verify-email")
            registry.add("POST", "/api/v1/account/delete/cancel")
            registry.add("POST", "/api/v1/account/password/forgot")
            registry.add("POST", "/api/v1/account/password/reset")
            registry.add("GET", "/api/v1/account/password/policy")
            registry.add("GET", "/api/v1/auth/methods")
        }

    companion object {
        /** `legal` 의 재동의 인터셉터는 기본 순서(0) — 주소 확인이 먼저다 */
        const val EMAIL_REQUIRED_ORDER = -100
    }
}
