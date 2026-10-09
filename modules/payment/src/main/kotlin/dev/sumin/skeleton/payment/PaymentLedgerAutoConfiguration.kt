package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.erasure.AccountDataExporter
import dev.sumin.skeleton.crypto.TextEncryptor
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 결제 장부 서비스(스펙 §4.3). **[PaymentLedgerStore] 빈이 없으면 이 설정은 통째로 빠진다 — `payment` 는 장부가 생기기 전과 한 글자도 다르지 않다.**
 * 저장소 · 작업 포트([PaymentTasks])는 `payment-jdbc` 가 내놓는다(없으면 시작이 실패하고 빈 이름을 말한다). 모든 빈은 앱이 같은 타입의 빈으로 갈아 끼운다.
 * 주인 경보 연동은 [dev.sumin.skeleton.payment.alert.PaymentAlertAutoConfiguration] 이 이 설정보다 먼저 등록해 [PaymentReviewNotifier] 기본을 물린다.
 */
@AutoConfiguration(
    after = [PaymentAutoConfiguration::class],
    afterName = [
        "dev.sumin.skeleton.payment.jdbc.PaymentJdbcAutoConfiguration",
        "dev.sumin.skeleton.payment.alert.PaymentAlertAutoConfiguration",
    ],
)
@ConditionalOnBean(PaymentLedgerStore::class)
class PaymentLedgerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun paymentLedgerWriter(time: ObjectProvider<TimeProvider>): PaymentLedgerWriter =
        PaymentLedgerWriter(time.getIfAvailable { TimeProvider.systemUtc() })

    /** 지급 · 회수를 하는 앱 리스너가 없으면 아무것도 하지 않는다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentOrderListener(): PaymentOrderListener = PaymentOrderListener.NONE

    /** 경보 연동(`alert`)이 없으면 "확인 필요"는 줄과 로그에만 남는다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentReviewNotifier(): PaymentReviewNotifier = PaymentReviewNotifier.NONE

    /** 환불 맞추기(스펙 §6.1 의 4 · 5) — 결제사의 취소 목록과 환불 줄을 맞춘다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentRefundConvergence(writer: PaymentLedgerWriter, tasks: PaymentTasks, review: PaymentReviewNotifier): PaymentRefundConvergence =
        LedgerRefundConvergence(writer, tasks, review)

    @Bean
    @ConditionalOnMissingBean
    fun paymentConvergence(
        writer: PaymentLedgerWriter,
        tasks: PaymentTasks,
        review: PaymentReviewNotifier,
        refunds: PaymentRefundConvergence,
    ): PaymentConvergence = PaymentConvergence(writer, tasks, review, refunds)

    @Bean
    @ConditionalOnMissingBean
    fun paymentFulfillment(
        store: PaymentLedgerStore,
        listener: PaymentOrderListener,
        writer: PaymentLedgerWriter,
        subscriptions: ObjectProvider<SubscriptionLifecycle>,
    ): PaymentFulfillment = PaymentFulfillment(store, listener, writer, subscriptions.getIfAvailable())

    /** [PaymentCatalog] 빈이 없으면 서비스는 있되 주문 만들기가 [PaymentUnavailableException] 이다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentOrders(
        store: PaymentLedgerStore,
        tasks: PaymentTasks,
        router: PaymentProviderRouter,
        catalog: ObjectProvider<PaymentCatalog>,
        convergence: PaymentConvergence,
        fulfillment: PaymentFulfillment,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
    ): PaymentOrders = PaymentOrders(store, tasks, router, catalog.getIfAvailable(), convergence, fulfillment, writer, properties)

    @Bean
    @ConditionalOnMissingBean
    fun orderExpireTaskHandler(store: PaymentLedgerStore, router: PaymentProviderRouter, convergence: PaymentConvergence): OrderExpireTaskHandler =
        OrderExpireTaskHandler(store, router, convergence)

    @Bean
    @ConditionalOnMissingBean
    fun orderReconcileTaskHandler(store: PaymentLedgerStore, router: PaymentProviderRouter, convergence: PaymentConvergence): OrderReconcileTaskHandler =
        OrderReconcileTaskHandler(store, router, convergence)

    @Bean
    @ConditionalOnMissingBean
    fun orderFulfilTaskHandler(fulfillment: PaymentFulfillment, store: PaymentLedgerStore, convergence: PaymentConvergence): OrderFulfilTaskHandler =
        OrderFulfilTaskHandler(fulfillment, store, convergence)

    @Bean
    @ConditionalOnMissingBean
    fun paymentRefundApplier(store: PaymentLedgerStore, listener: PaymentOrderListener, fulfillment: PaymentFulfillment, writer: PaymentLedgerWriter): PaymentRefundApplier =
        PaymentRefundApplier(store, listener, fulfillment, writer)

    /** 환불 서비스 — 본인 요청 · 거둠, 운영자 승인 · 거절 · 직접 환불 · 결제사와 맞추기. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentRefunds(
        store: PaymentLedgerStore,
        tasks: PaymentTasks,
        router: PaymentProviderRouter,
        convergence: PaymentConvergence,
        applier: PaymentRefundApplier,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
    ): PaymentRefunds = PaymentRefunds(store, tasks, router, convergence, applier, writer, properties)

    @Bean
    @ConditionalOnMissingBean
    fun refundReconcileTaskHandler(
        store: PaymentLedgerStore,
        router: PaymentProviderRouter,
        convergence: PaymentConvergence,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
    ): RefundReconcileTaskHandler = RefundReconcileTaskHandler(store, router, convergence, writer, properties)

    @Bean
    @ConditionalOnMissingBean
    fun refundApplyTaskHandler(applier: PaymentRefundApplier, store: PaymentLedgerStore, convergence: PaymentConvergence): RefundApplyTaskHandler =
        RefundApplyTaskHandler(applier, store, convergence)

    /** 웹훅 수신함 — 주소 확인 · 줄 넣기 + 처리 작업 · 운영자의 다시 처리와 목록. 수신 경로(`webhook.enabled`)와 무관하게 선다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentWebhookInbox(
        store: PaymentLedgerStore,
        tasks: PaymentTasks,
        router: PaymentProviderRouter,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
    ): PaymentWebhookInbox = PaymentWebhookInbox(store, tasks, router, writer, properties)

    @Bean
    @ConditionalOnMissingBean
    fun paymentWebhookTaskHandler(
        store: PaymentLedgerStore,
        router: PaymentProviderRouter,
        convergence: PaymentConvergence,
        writer: PaymentLedgerWriter,
    ): PaymentWebhookTaskHandler = PaymentWebhookTaskHandler(store, router, convergence, writer)

    /** 서명 없는 결제사의 웹훅인데 주소의 비밀 조각이 비었거나 약하면 보호 환경에서 기동 실패(스펙 §9.3). 웹훅 경로를 껐으면 서지 않는다. */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentWebhookDeployGuard"])
    @ConditionalOnProperty(prefix = "skeleton.payment.webhook", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun paymentWebhookDeployGuard(properties: PaymentProperties, gateways: ObjectProvider<PaymentWebhookGateway>): PaymentWebhookDeployGuard =
        PaymentWebhookDeployGuard(properties) {
            gateways.orderedStream().filter { !it.signed && (properties.providers[it.providerId.trim().lowercase()]?.enabled ?: true) }.map { it.providerId }.toList()
        }

    /** 큐 어댑터가 [PaymentTaskRunner.types] 마다 작업 처리기 하나를 등록하고 [PaymentTaskRunner.run] 을 부른다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentTaskRunner(handlers: List<PaymentTaskHandler>): PaymentTaskRunner = DefaultPaymentTaskRunner(handlers)

    /** 계정 탈퇴 고리 — 카드 두 칸을 지우고 주문 줄은 남긴다(스펙 §8.2). 플랫폼의 계약만 구현하므로 `account` 모듈이 없어도 된다(아무도 부르지 않을 뿐). */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentAccountErasure"])
    fun paymentAccountErasure(store: PaymentLedgerStore, writer: PaymentLedgerWriter, properties: PaymentProperties): AccountErasureListener =
        PaymentAccountErasure(store, writer, properties.erasure.accountId)

    @Bean
    @ConditionalOnMissingBean(name = ["paymentAccountDataExporter"])
    fun paymentAccountDataExporter(store: PaymentLedgerStore, router: PaymentProviderRouter, writer: PaymentLedgerWriter, properties: PaymentProperties): AccountDataExporter =
        PaymentAccountDataExporter(PaymentQueries(store, router, writer, properties))

    /**
     * 구독(스펙 `2026-10-09-subscription-design.md`) — **`skeleton.payment.subscription.enabled=true` 일 때만** 서고, 켰는데 앱이 [SubscriptionPlans] · 빌링 능력을 가진 결제사 · 빌링키를 암호화할
     * `crypto` 키([TextEncryptor])를 내지 않으면 **기동 실패**다(빠뜨린 채 뜬 구독은 카드를 받고도 과금할 수 없다).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "skeleton.payment.subscription", name = ["enabled"], havingValue = "true")
    class SubscriptionConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun subscriptionLifecycle(tasks: PaymentTasks, writer: PaymentLedgerWriter, properties: PaymentProperties, review: PaymentReviewNotifier): SubscriptionLifecycle =
            SubscriptionLifecycle(tasks, writer, properties, review)

        @Bean
        @ConditionalOnMissingBean
        fun subscriptionCharges(store: PaymentLedgerStore, convergence: PaymentConvergence, fulfillment: PaymentFulfillment, writer: PaymentLedgerWriter): SubscriptionCharges =
            SubscriptionCharges(store, convergence, fulfillment, writer)

        @Bean
        @ConditionalOnMissingBean
        fun paymentSubscriptions(
            store: PaymentLedgerStore,
            router: PaymentProviderRouter,
            plans: ObjectProvider<SubscriptionPlans>,
            encryptor: ObjectProvider<TextEncryptor>,
            billing: ObjectProvider<PaymentBillingGateway>,
            lifecycle: SubscriptionLifecycle,
            charges: SubscriptionCharges,
            tasks: PaymentTasks,
            writer: PaymentLedgerWriter,
            properties: PaymentProperties,
        ): PaymentSubscriptions {
            val plansBean = plans.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a SubscriptionPlans bean (the app owns the plans and prices)")
            val encryptorBean = encryptor.getIfAvailable()
                ?: error("skeleton.payment.subscription.enabled=true needs an encryption key to store billing keys: set skeleton.crypto.keys (the crypto module's TextEncryptor)")
            check(billing.orderedStream().findAny().isPresent) {
                "skeleton.payment.subscription.enabled=true needs a payment provider with the billing capability (PaymentBillingGateway), e.g. payment-toss"
            }
            return PaymentSubscriptions(store, router, plansBean, encryptorBean, lifecycle, charges, tasks, writer, properties)
        }

        @Bean
        @ConditionalOnMissingBean
        fun subscriptionRevokeTaskHandler(store: PaymentLedgerStore, router: PaymentProviderRouter, encryptor: ObjectProvider<TextEncryptor>, writer: PaymentLedgerWriter): SubscriptionRevokeTaskHandler =
            SubscriptionRevokeTaskHandler(store, router, encryptor.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a TextEncryptor (skeleton.crypto.keys)"), writer)
    }
}
