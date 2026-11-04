package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentStorePurchases
import dev.sumin.skeleton.payment.PaymentSubscriptionAutoConfiguration
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.web.PaymentCallers
import dev.sumin.skeleton.payment.web.PaymentCountryRouting
import dev.sumin.skeleton.payment.web.PaymentHttpAutoConfiguration
import dev.sumin.skeleton.payment.web.PaymentOrderRateLimiter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 앱용 인앱결제 HTTP(스펙 §3) - `skeleton.payment-iap.enabled=true` 일 때만. 설정이 잘못되면(웹 채널인데 링크가 없다 · 스토어 상품 id 가 겹친다) **기동이 실패한다**.
 * 경로는 서블릿 웹 앱이고 Spring Security 가 클래스패스에 있고 **장부([PaymentStorePurchases] 빈)가 있을 때만** 선다 - 장부가 없으면 HTTP 는 하나도 없다.
 */
@AutoConfiguration(
    after = [PaymentLedgerAutoConfiguration::class, PaymentSubscriptionAutoConfiguration::class, PaymentHttpAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.auth.handoff.WebHandoffAutoConfiguration"],
)
@EnableConfigurationProperties(IapProperties::class)
@ConditionalOnProperty(prefix = "skeleton.payment-iap", name = ["enabled"], havingValue = "true")
class IapAutoConfiguration {
    /** 설정 검사 - 빈 하나가 만들어지는 순간 던져 앱이 뜨지 않게 한다. 상품 지도 · 지역 정책을 앱이 바꿔 끼워도 검사는 설정에 대해 돈다. */
    @Bean
    fun iapConfigCheck(properties: IapProperties): IapConfigCheck = IapConfigCheck(properties.also { it.validate() })

    @Bean
    @ConditionalOnMissingBean
    fun iapProducts(properties: IapProperties): IapProducts = ConfiguredIapProducts(properties)

    @Bean
    @ConditionalOnMissingBean
    fun iapChannelPolicy(properties: IapProperties): IapChannelPolicy = ConfiguredIapChannelPolicy(properties)

    // 중첩 설정에는 @Configuration 표지를 달지 않는다 - @Bean 메서드만으로 중첩 설정으로 읽힌다 (ModuleRegistrationRulesTest)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
    @ConditionalOnBean(PaymentStorePurchases::class, PaymentCallers::class, PaymentCountryRouting::class)
    class Http {
        @Bean
        @ConditionalOnMissingBean
        fun iapPurchases(
            router: PaymentProviderRouter,
            purchases: PaymentStorePurchases,
            products: IapProducts,
            catalog: ObjectProvider<PaymentCatalog>,
            plans: ObjectProvider<SubscriptionPlans>,
            ledger: PaymentLedgerStore,
        ): IapPurchases = IapPurchases(router, purchases, products, catalog, plans, ledger)

        @Bean
        @ConditionalOnMissingBean
        fun iapController(
            callers: PaymentCallers,
            purchases: IapPurchases,
            products: IapProducts,
            channels: IapChannelPolicy,
            routing: PaymentCountryRouting,
            router: PaymentProviderRouter,
            limiter: PaymentOrderRateLimiter,
            clientIps: ObjectProvider<ClientIps>,
            properties: IapProperties,
        ): IapController = IapController(callers, purchases, products, channels, routing, router, limiter, clientIps.getIfAvailable { ClientIps() }, properties)
    }

    /**
     * 앱 -> 웹 결제 자동 로그인 주소 - `Http` 와 같은 조건에 `auth-web-handoff` 의 발급자 빈이 있을 때만 선다 (없으면 이 경로는 없다).
     * `Http` 안에 두지 않는다: 표지 없는 중첩 설정의 중첩 클래스는 읽히지 않는다.
     */
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
    @ConditionalOnBean(
        PaymentStorePurchases::class, PaymentCallers::class, PaymentCountryRouting::class,
        dev.sumin.skeleton.common.account.WebHandoffIssuer::class,
    )
    class WebCheckoutLink {
        @Bean
        @ConditionalOnMissingBean
        fun iapWebCheckoutLinkController(
            callers: PaymentCallers,
            channels: IapChannelPolicy,
            routing: PaymentCountryRouting,
            handoff: dev.sumin.skeleton.common.account.WebHandoffIssuer,
            properties: IapProperties,
        ): IapWebCheckoutLinkController = IapWebCheckoutLinkController(callers, channels, routing, handoff, properties)
    }
}

/** 기동 때 설정을 검사했다는 표시 - 이 빈이 만들어지지 못하면 앱이 뜨지 않는다. */
class IapConfigCheck(val properties: IapProperties)
