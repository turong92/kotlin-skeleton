package dev.sumin.skeleton.auth.mfa.web

import dev.sumin.skeleton.auth.mfa.ConfiguredMfaPolicy
import dev.sumin.skeleton.auth.mfa.MfaAutoConfiguration
import dev.sumin.skeleton.auth.mfa.MfaPolicy
import dev.sumin.skeleton.auth.mfa.MfaProperties
import dev.sumin.skeleton.auth.mfa.MfaRequirement
import dev.sumin.skeleton.auth.mfa.MfaService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 등록 필수 문 - 경로 스위치(`http.enabled`)와 **떼어** 둔다: 앱이 자기 컨트롤러를 쓰려고 경로를 닫아도 `required-for` 는 조용히 무시되지 않는다 */
@AutoConfiguration(after = [MfaAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(MfaService::class)
class MfaEnrollmentGateAutoConfiguration {
    /**
     * 필수 문 - 설정이 `required-for: NONE`(기본)이고 정책 빈도 기본 그대로면 아무것도 걸지 않는다(요청마다 하는 일이 없다). 앱이 정책 빈을 갈아 끼웠거나 설정이 필수를 켰을 때만 선다.
     * 순서는 이메일 필수(-100) · 아이디(-110) 뒤. 경로 스위치(`http.enabled`)와 상관없이 선다(앱이 자기 컨트롤러로 등록을 받는 경우 포함).
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
