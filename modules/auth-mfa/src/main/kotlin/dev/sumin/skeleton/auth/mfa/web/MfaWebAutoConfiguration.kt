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
}
