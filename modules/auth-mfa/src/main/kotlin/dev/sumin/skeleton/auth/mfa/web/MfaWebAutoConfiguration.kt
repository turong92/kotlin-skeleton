package dev.sumin.skeleton.auth.mfa.web

import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.mfa.ConfiguredMfaPolicy
import dev.sumin.skeleton.auth.mfa.MfaAutoConfiguration
import dev.sumin.skeleton.auth.mfa.MfaPolicy
import dev.sumin.skeleton.auth.mfa.MfaProperties
import dev.sumin.skeleton.auth.mfa.MfaRequirement
import dev.sumin.skeleton.auth.mfa.MfaService
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
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

/** 서블릿 웹 앱이고 Spring Security 가 있을 때만. `skeleton.auth-mfa.http.enabled=false` 로 경로를 닫는다(로그인 두 번째 단계 고리는 그대로 서 있다) */
@AutoConfiguration(after = [MfaAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(MfaService::class)
@ConditionalOnProperty(prefix = "skeleton.auth-mfa.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class MfaWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun mfaController(service: MfaService, tokens: AuthTokenResponseFactory, clientIps: ObjectProvider<ClientIps>): MfaController =
        MfaController(service, tokens, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean(name = ["mfaPublicEndpointContributor"])
    fun mfaPublicEndpointContributor(properties: MfaProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "${properties.http.basePath}/verify") }

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.auth-mfa.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun mfaAdminController(service: MfaService, access: OperatorAccess, audit: OperatorAuditLog): MfaAdminController = MfaAdminController(service, access, audit)

    /**
     * 필수 문 - 설정이 `required-for: NONE`(기본)이고 정책 빈도 기본 그대로면 아무것도 걸지 않는다(요청마다 하는 일이 없다). 앱이 정책 빈을 갈아 끼웠거나 설정이 필수를 켰을 때만 선다.
     * 순서는 이메일 필수(-100) · 아이디(-110) 뒤. 사용자 경로 스위치(`http.enabled`)와 함께 꺼진다 - 등록 경로 없이 문만 서면 아무도 못 들어오므로.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["mfaEnrollmentRequiredConfigurer"])
    fun mfaEnrollmentRequiredConfigurer(requirement: MfaRequirement, policy: MfaPolicy, properties: MfaProperties): WebMvcConfigurer {
        val interceptor = EnrollmentRequiredInterceptor(requirement, properties.http.basePath, properties.required.extraExcludePaths)
        val active = properties.requiredFor != MfaProperties.RequiredFor.NONE || policy !is ConfiguredMfaPolicy
        return object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                if (active) registry.addInterceptor(interceptor).order(ENROLLMENT_ORDER)
            }
        }
    }

    companion object {
        const val ENROLLMENT_ORDER = -90
    }
}
