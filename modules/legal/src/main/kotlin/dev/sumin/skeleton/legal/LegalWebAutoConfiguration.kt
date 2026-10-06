package dev.sumin.skeleton.legal

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.legal.web.ConsentController
import dev.sumin.skeleton.legal.web.LegalAdminController
import dev.sumin.skeleton.legal.web.LegalCallers
import dev.sumin.skeleton.legal.web.LegalDocumentController
import dev.sumin.skeleton.legal.web.ReconsentInterceptor
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
 * 약관 · 동의 HTTP 엔드포인트(`skeleton.legal.http.base-path`, 기본 `/api/v1/legal`) — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 * `skeleton.legal.http.enabled=false` 로 끈다. 문서 읽기는 공개 경로로 연다. 재동의 필터는 `skeleton.legal.reconsent.enabled=true` 일 때만.
 */
@AutoConfiguration(after = [LegalAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.legal.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LegalWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun legalCallers(properties: LegalProperties): LegalCallers = LegalCallers(properties.adminRole)

    @Bean
    @ConditionalOnMissingBean
    fun legalDocumentController(catalog: LegalCatalog, rules: LegalRules, properties: LegalProperties, time: ObjectProvider<TimeProvider>): LegalDocumentController =
        LegalDocumentController(catalog, rules, time.getIfAvailable { TimeProvider.systemUtc() }, properties.http.cacheMaxAge)

    @Bean
    @ConditionalOnMissingBean
    fun consentController(service: ConsentService, store: ConsentStore, callers: LegalCallers, clientIps: ObjectProvider<ClientIps>): ConsentController =
        ConsentController(service, store, callers, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean
    fun legalAdminController(
        store: ConsentStore,
        catalog: LegalCatalog,
        ledger: LegalLedger,
        rules: LegalRules,
        callers: LegalCallers,
        time: ObjectProvider<TimeProvider>,
    ): LegalAdminController = LegalAdminController(store, catalog, ledger, rules, time.getIfAvailable { TimeProvider.systemUtc() }, callers)

    /** 문서 읽기는 로그인 없이 */
    @Bean
    @ConditionalOnMissingBean(name = ["legalPublicEndpointContributor"])
    fun legalPublicEndpointContributor(properties: LegalProperties): PublicEndpointContributor = PublicEndpointContributor { registry ->
        registry.add("GET", "${properties.http.basePath}/documents")
        registry.add("GET", "${properties.http.basePath}/documents/**")
    }

    @Bean
    @ConditionalOnMissingBean(name = ["legalReconsentConfigurer"])
    @ConditionalOnProperty(prefix = "skeleton.legal.reconsent", name = ["enabled"], havingValue = "true")
    fun legalReconsentConfigurer(service: ConsentService, callers: LegalCallers, properties: LegalProperties): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(ReconsentInterceptor(service, callers))
                .addPathPatterns(properties.reconsent.includePaths)
                .excludePathPatterns(properties.reconsent.excludePaths)
        }
    }
}
