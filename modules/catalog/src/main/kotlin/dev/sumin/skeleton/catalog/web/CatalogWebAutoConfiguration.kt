package dev.sumin.skeleton.catalog.web

import dev.sumin.skeleton.catalog.CatalogAdmin
import dev.sumin.skeleton.catalog.CatalogAutoConfiguration
import dev.sumin.skeleton.catalog.CatalogProperties
import dev.sumin.skeleton.catalog.CatalogReader
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.web.PaymentCountryRouting
import dev.sumin.skeleton.payment.web.PaymentHttpAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 공개 읽기(`skeleton.catalog.http.enabled=true` 일 때만, 로그인 없이)와 운영자 경로(`skeleton.catalog.admin.enabled=true` 일 때만, 꺼져 있으면 404).
 * 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만. 통화를 정하는 나라 라우팅은 결제 HTTP 설정이 낸 빈이라 그 뒤에 선다.
 */
@AutoConfiguration(after = [CatalogAutoConfiguration::class, PaymentHttpAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class CatalogWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(CatalogReader::class)
    @ConditionalOnProperty(prefix = "skeleton.catalog.http", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun catalogController(reader: CatalogReader, routing: ObjectProvider<PaymentCountryRouting>): CatalogController =
        CatalogController(reader) { routing.getIfAvailable() }

    @Bean
    @ConditionalOnBean(CatalogReader::class)
    @ConditionalOnProperty(prefix = "skeleton.catalog.http", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(name = ["catalogPublicEndpointContributor"])
    fun catalogPublicEndpointContributor(properties: CatalogProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("GET", "${properties.http.basePath}/products") }

    @Bean
    @ConditionalOnBean(CatalogAdmin::class)
    @ConditionalOnProperty(prefix = "skeleton.catalog.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun catalogAdminController(admin: CatalogAdmin, access: OperatorAccess, time: ObjectProvider<TimeProvider>, router: ObjectProvider<PaymentProviderRouter>): CatalogAdminController =
        CatalogAdminController(admin, access, time.getIfAvailable { TimeProvider.systemUtc() }) {
            // 가격 id 를 쓰는 켜진 결제사 = 서명된 웹훅이 곧 사실인 결제사(거래를 먼저 만드는 Paddle 꼴)
            router.getIfAvailable()?.providerIds(PaymentPushGateway::class).orEmpty()
        }
}
