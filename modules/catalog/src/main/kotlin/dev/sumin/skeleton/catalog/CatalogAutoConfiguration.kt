package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.search.SearchIndexer
import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentSubscriptionAutoConfiguration
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.ProviderPriceRefs
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.web.PaymentHttpAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 상품 · 가격 표와 결제 계약 구현. 저장소([CatalogStore])는 `catalog-jdbc` 가 내놓는다 - 없으면 서비스 빈이 켜지지 않는다.
 * 결제 쪽 계약 셋([SubscriptionPlans] · [PaymentCatalog] · [ProviderPriceRefs])은 **앱이 같은 타입의 빈을 두면 앱 것이 이긴다** -
 * 그래서 이 설정은 결제 자동설정(`payment` 가 이 빈들을 찾는다)보다 **먼저** 서고, 앱의 빈이 이미 등록된 뒤에 `ConditionalOnMissingBean` 을 본다.
 * `payment` 는 이 모듈을 모른다 - 이 모듈이 결제 계약을 구현할 뿐이다.
 */
@AutoConfiguration(
    afterName = ["dev.sumin.skeleton.catalog.jdbc.CatalogJdbcAutoConfiguration"],
    before = [PaymentLedgerAutoConfiguration::class, PaymentSubscriptionAutoConfiguration::class, PaymentHttpAutoConfiguration::class],
)
@EnableConfigurationProperties(CatalogProperties::class)
class CatalogAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["catalogPermissionDeclaration"])
    fun catalogPermissionDeclaration(): PermissionDeclaration = CatalogPermissions.declaration()

    @Bean
    @ConditionalOnBean(CatalogStore::class)
    @ConditionalOnMissingBean
    fun catalogAdmin(store: CatalogStore, time: ObjectProvider<TimeProvider>, audit: ObjectProvider<OperatorAuditLog>, router: ObjectProvider<PaymentProviderRouter>, search: ObjectProvider<SearchIndexer>, properties: CatalogProperties): CatalogAdmin =
        CatalogAdmin(store, time.getIfAvailable { TimeProvider.systemUtc() }, audit.getIfAvailable { OperatorAuditLog { } }, providers = {
            // 가격 id 를 쓰는 켜진 결제사 = 서명된 웹훅이 곧 사실인 결제사(거래를 먼저 만드는 Paddle 꼴)
            router.getIfAvailable()?.providerIds(PaymentPushGateway::class).orEmpty()
        }, searchIndexer = { search.ifAvailable }, defaultLocale = properties.defaultLocale)

    /** 검색 모듈이 이 종류(`product`)를 다시 읽을 때 쓴다 - 검색을 안 붙인 앱에서는 아무도 부르지 않는 빈이다 */
    @Bean
    @ConditionalOnBean(CatalogStore::class)
    @ConditionalOnMissingBean(name = ["catalogSearchSource"])
    fun catalogSearchSource(store: CatalogStore, properties: CatalogProperties): SearchSource = CatalogSearchSource(store, properties.defaultLocale)

    @Bean
    @ConditionalOnBean(CatalogStore::class)
    @ConditionalOnMissingBean
    fun catalogReader(store: CatalogStore, time: ObjectProvider<TimeProvider>): CatalogReader =
        CatalogReader(store, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnBean(CatalogStore::class)
    @ConditionalOnMissingBean(SubscriptionPlans::class)
    fun catalogSubscriptionPlans(store: CatalogStore, time: ObjectProvider<TimeProvider>, properties: CatalogProperties): SubscriptionPlans =
        CatalogSubscriptionPlans(store, time.getIfAvailable { TimeProvider.systemUtc() }, properties)

    @Bean
    @ConditionalOnBean(CatalogStore::class)
    @ConditionalOnMissingBean(PaymentCatalog::class)
    fun catalogPaymentCatalog(store: CatalogStore, time: ObjectProvider<TimeProvider>, properties: CatalogProperties): PaymentCatalog =
        CatalogPaymentCatalog(store, time.getIfAvailable { TimeProvider.systemUtc() }, properties)

    @Bean
    @ConditionalOnBean(CatalogStore::class)
    @ConditionalOnMissingBean(ProviderPriceRefs::class)
    fun catalogProviderPriceRefs(store: CatalogStore): ProviderPriceRefs = CatalogProviderPriceRefs(store)
}
