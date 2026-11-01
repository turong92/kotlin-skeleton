package dev.sumin.skeleton.inquiry.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.inquiry.InquiryAdminService
import dev.sumin.skeleton.inquiry.InquiryAutoConfiguration
import dev.sumin.skeleton.inquiry.InquiryService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 문의 HTTP - 서블릿 웹 앱이고 Spring Security 가 클래스패스에 있을 때만. 공개 경로는 `skeleton.inquiry.enabled=false` 로 끄고,
 * 운영자 경로는 `skeleton.inquiry.admin.enabled=true` 일 때만 켜진다(꺼져 있으면 404).
 */
@AutoConfiguration(after = [InquiryAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class InquiryWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(InquiryService::class)
    @ConditionalOnProperty(prefix = "skeleton.inquiry", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun inquiryController(service: InquiryService, clientIps: ObjectProvider<ClientIps>): InquiryController =
        InquiryController(service, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.inquiry", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = ["inquiryPublicEndpointContributor"])
    fun inquiryPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/inquiries") }

    @Bean
    @ConditionalOnBean(InquiryAdminService::class)
    @ConditionalOnProperty(prefix = "skeleton.inquiry.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun inquiryAdminController(inquiries: InquiryAdminService, access: OperatorAccess, audit: OperatorAuditLog): InquiryAdminController =
        InquiryAdminController(inquiries, access, audit)
}
