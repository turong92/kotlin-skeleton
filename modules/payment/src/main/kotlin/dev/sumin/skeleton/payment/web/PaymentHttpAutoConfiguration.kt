package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentOperatorPolicy
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentQueries
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.PaymentSubscriptions
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentLedgerWriter
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

/**
 * 결제 사용자 · 운영자 HTTP(스펙 §7) — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 **장부([PaymentOrders] 빈)가 있을 때만**. 장부가 없으면 빈이 하나도 늘지 않는다.
 * 사용자 경로는 `skeleton.payment.http.enabled=false` 로 끄고(웹훅 경로는 영향 없음 — 그것은 `webhook.enabled`), 운영자 경로는 `skeleton.payment.admin.enabled=true` 일 때만 선다.
 * 주문 만들기는 [PaymentCatalog] 빈이 있을 때만. 인증은 앱의 보안 설정이 건다(auth 의 기본 체인은 모든 경로에 인증을 요구한다).
 */
@AutoConfiguration(after = [PaymentLedgerAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(PaymentOrders::class)
class PaymentHttpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun paymentQueries(store: PaymentLedgerStore, router: PaymentProviderRouter, writer: PaymentLedgerWriter, properties: PaymentProperties): PaymentQueries =
        PaymentQueries(store, router, writer, properties)

    @Bean
    @ConditionalOnMissingBean
    fun paymentCallers(policy: ObjectProvider<PaymentOperatorPolicy>): PaymentCallers = PaymentCallers(policy.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.payment.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun paymentController(
        orders: PaymentOrders,
        refunds: PaymentRefunds,
        queries: PaymentQueries,
        router: PaymentProviderRouter,
        catalog: ObjectProvider<PaymentCatalog>,
        properties: PaymentProperties,
        callers: PaymentCallers,
    ): PaymentController = PaymentController(orders, refunds, queries, router, catalog, properties, callers)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PaymentCatalog::class)
    @ConditionalOnProperty(prefix = "skeleton.payment.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun paymentOrderController(
        orders: PaymentOrders,
        callers: PaymentCallers,
        limiter: PaymentOrderRateLimiter,
        clientIps: ObjectProvider<ClientIps>,
    ): PaymentOrderController = PaymentOrderController(orders, callers, limiter, clientIps.getIfAvailable { ClientIps() })

    /** 구독 경로 — 구독을 켠 앱([PaymentSubscriptions] 빈)에서만. 만들기는 주문 만들기와 같은 요청 수 한도를 쓴다. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PaymentSubscriptions::class)
    @ConditionalOnProperty(prefix = "skeleton.payment.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun paymentSubscriptionController(
        subscriptions: PaymentSubscriptions,
        callers: PaymentCallers,
        limiter: PaymentOrderRateLimiter,
        clientIps: ObjectProvider<ClientIps>,
    ): PaymentSubscriptionController = PaymentSubscriptionController(subscriptions, callers, limiter, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean
    fun paymentOrderRateLimiter(stores: ObjectProvider<RateLimitStore>, time: ObjectProvider<TimeProvider>, properties: PaymentProperties): PaymentOrderRateLimiter {
        val fallback by lazy { InMemoryFixedWindowRateLimitStore() }
        return PaymentOrderRateLimiter({ stores.getIfAvailable { fallback } }, time.getIfAvailable { TimeProvider.systemUtc() }, properties.http.orderRateLimit)
    }

    /** 운영자 경로 — 꺼져 있으면(기본) 이 빈들이 없어 경로가 없다(404). 정책은 앱이 같은 타입의 빈으로 갈아 끼운다. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.payment.admin", name = ["enabled"], havingValue = "true")
    fun paymentAdminController(queries: PaymentQueries, refunds: PaymentRefunds, inbox: PaymentWebhookInbox): PaymentAdminController =
        PaymentAdminController(queries, refunds, inbox)

    /** 운영자 컨트롤러의 모든 핸들러에 접근 규칙을 거는 한 곳 — 핸들러가 손으로 부르지 않는다. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.payment.admin", name = ["enabled"], havingValue = "true")
    fun paymentOperatorInterceptor(callers: PaymentCallers): PaymentOperatorInterceptor = PaymentOperatorInterceptor(callers)

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.payment.admin", name = ["enabled"], havingValue = "true")
    fun paymentOperatorWebConfigurer(interceptor: PaymentOperatorInterceptor): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(interceptor)
        }
    }

    @Bean
    @ConditionalOnMissingBean(PaymentOperatorPolicy::class)
    @ConditionalOnProperty(prefix = "skeleton.payment.admin", name = ["enabled"], havingValue = "true")
    fun paymentOperatorPolicy(properties: PaymentProperties): PaymentOperatorPolicy = RolePaymentOperatorPolicy(properties.admin.role)
}
