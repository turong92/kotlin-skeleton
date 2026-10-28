package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.erasure.AccountDataExporter
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import dev.sumin.skeleton.crypto.TextEncryptor
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

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
    /** 할인 수단([DiscountSource] 빈 - 쿠폰 등)을 모은다. 수단이 하나도 없으면 아무것도 하지 않는다 - 이 모듈들을 안 붙인 앱의 결제는 그대로다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentDiscounts(sources: ObjectProvider<DiscountSource>, store: PaymentLedgerStore, time: ObjectProvider<TimeProvider>): PaymentDiscounts {
        val clock = time.getIfAvailable { TimeProvider.systemUtc() }
        return PaymentDiscounts({ sources.orderedStream().toList() }, store) { clock.now() }
    }

    /** 결제 면제 판정 - 권한 `payment.exempt` 를 platform 의 [OperatorAccess] 에 묻는다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentExemption(access: ObjectProvider<OperatorAccess>): PaymentExemption =
        PaymentExemption(access.getIfAvailable { NobodyIsExempt })   // 판정기(platform 의 OperatorAccess)가 없는 조립은 아무도 면제하지 않는다

    @Bean
    @ConditionalOnMissingBean
    fun paymentLedgerWriter(time: ObjectProvider<TimeProvider>, discounts: ObjectProvider<PaymentDiscounts>): PaymentLedgerWriter =
        PaymentLedgerWriter(time.getIfAvailable { TimeProvider.systemUtc() }).also { it.discounts = discounts.getIfAvailable() }

    /** 지급 · 회수를 하는 앱 리스너가 없으면 아무것도 하지 않는다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentOrderListener(): PaymentOrderListener = PaymentOrderListener.NONE

    /** 경보 연동(`alert`)이 없으면 "확인 필요"는 줄과 로그에만 남는다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentReviewNotifier(): PaymentReviewNotifier = PaymentReviewNotifier.NONE

    /** 경보 연동(`alert`)이 없으면 차지백은 환불 줄과 이력에만 남는다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentChargebackAlert(): PaymentChargebackAlert = PaymentChargebackAlert.NONE

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
        subscriptions: ObjectProvider<SubscriptionLifecycle>,
    ): PaymentConvergence = PaymentConvergence(writer, tasks, review, refunds).also { it.subscriptions = subscriptions.getIfAvailable() }

    @Bean
    @ConditionalOnMissingBean
    fun paymentFulfillment(
        store: PaymentLedgerStore,
        listener: PaymentOrderListener,
        writer: PaymentLedgerWriter,
        subscriptions: ObjectProvider<SubscriptionLifecycle>,
        discounts: ObjectProvider<PaymentDiscounts>,
    ): PaymentFulfillment = PaymentFulfillment(store, listener, writer, subscriptions.getIfAvailable(), discounts.getIfAvailable())

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
        discounts: ObjectProvider<PaymentDiscounts>,
        exemption: ObjectProvider<PaymentExemption>,
    ): PaymentOrders = PaymentOrders(store, tasks, router, catalog.getIfAvailable(), convergence, fulfillment, writer, properties, discounts = discounts.getIfAvailable(), exemption = exemption.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    fun orderExpireTaskHandler(store: PaymentLedgerStore, router: PaymentProviderRouter, convergence: PaymentConvergence): OrderExpireTaskHandler =
        OrderExpireTaskHandler(store, router, convergence)

    @Bean
    @ConditionalOnMissingBean
    fun orderReconcileTaskHandler(
        store: PaymentLedgerStore,
        router: PaymentProviderRouter,
        convergence: PaymentConvergence,
        subscriptions: ObjectProvider<SubscriptionLifecycle>,
    ): OrderReconcileTaskHandler = OrderReconcileTaskHandler(store, router, convergence).also { it.subscriptions = subscriptions.getIfAvailable() }

    @Bean
    @ConditionalOnMissingBean
    fun orderFulfilTaskHandler(fulfillment: PaymentFulfillment, store: PaymentLedgerStore, convergence: PaymentConvergence): OrderFulfilTaskHandler =
        OrderFulfilTaskHandler(fulfillment, store, convergence)

    @Bean
    @ConditionalOnMissingBean
    fun paymentRefundApplier(
        store: PaymentLedgerStore,
        listener: PaymentOrderListener,
        fulfillment: PaymentFulfillment,
        writer: PaymentLedgerWriter,
        subscriptions: ObjectProvider<SubscriptionLifecycle>,
    ): PaymentRefundApplier = PaymentRefundApplier(store, listener, fulfillment, writer, subscriptions.getIfAvailable())

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
        tasksNow: ObjectProvider<PaymentTasksNow>,
    ): PaymentWebhookInbox {
        val inline = properties.webhook.inline.takeIf { it.enabled }?.let { config -> tasksNow.getIfAvailable()?.let { PaymentWebhookInline(it, config.timeBudget) } }
        return PaymentWebhookInbox(store, tasks, router, writer, properties, inline)
    }

    /** 서명된 웹훅이 곧 사실인 결제사(Paddle)의 사건을 장부에 옮긴다. 구독을 켠 조립이면 구독 사건도 반영한다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentPushedEvents(
        store: PaymentLedgerStore,
        tasks: PaymentTasks,
        writer: PaymentLedgerWriter,
        subscriptions: ObjectProvider<SubscriptionLifecycle>,
        review: PaymentReviewNotifier,
        chargebacks: PaymentChargebackAlert,
    ): PaymentPushedEvents = PaymentPushedEvents(store, tasks, writer, subscriptions.getIfAvailable(), review, chargebacks)

    @Bean
    @ConditionalOnMissingBean
    fun paymentWebhookTaskHandler(
        store: PaymentLedgerStore,
        router: PaymentProviderRouter,
        convergence: PaymentConvergence,
        writer: PaymentLedgerWriter,
        pushed: PaymentPushedEvents,
    ): PaymentWebhookTaskHandler = PaymentWebhookTaskHandler(store, router, convergence, writer, pushed)

    /** 서명 없는 결제사의 웹훅인데 주소의 비밀 조각이 비었거나 약하면 보호 환경에서 기동 실패(스펙 §9.3). 웹훅 경로를 껐으면 서지 않는다. */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentWebhookDeployGuard"])
    @ConditionalOnProperty(prefix = "skeleton.payment.webhook", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @Conditional(ProjectsNotConfigured::class)
    fun paymentWebhookDeployGuard(properties: PaymentProperties, gateways: ObjectProvider<PaymentWebhookGateway>): PaymentWebhookDeployGuard =
        PaymentWebhookDeployGuard(properties) {
            gateways.orderedStream().filter { !it.signed && (properties.providers[it.providerId.trim().lowercase()]?.enabled ?: true) }.map { it.providerId }.toList()
        }

    /** 프로젝트 모드: 프로젝트마다 그 프로젝트의 결제사 · 비밀 조각으로 같은 규칙을 본다 — 메시지 앞에 `project '<id>': ` */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentProjectWebhookDeployGuard"])
    @ConditionalOnProperty(prefix = "skeleton.payment.webhook", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @Conditional(ProjectsConfigured::class)
    fun paymentProjectWebhookDeployGuard(router: PaymentProviderRouter): DeployGuard = object : DeployGuard {
        override val name = "payment"
        override fun problems(context: DeployContext) = perProject { it.problems(context) }
        override fun warnings(context: DeployContext) = perProject { it.warnings(context) }
        private fun perProject(of: (PaymentWebhookDeployGuard) -> List<String>) = router.allRoutes().flatMap { routes ->
            val guard = PaymentWebhookDeployGuard(routes.properties) {
                routes.gateways.filterIsInstance<PaymentWebhookGateway>().filter { !it.signed && (routes.properties.providers[it.providerId.trim().lowercase()]?.enabled ?: true) }.map { it.providerId }
            }
            of(guard).map { "project '${routes.projectId}': $it" }
        }
    }

    /** 로컬 나라 스위치가 보호 환경에서 켜져 있으면 기동 실패. */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentCountryDeployGuard"])
    fun paymentCountryDeployGuard(properties: PaymentProperties): PaymentCountryDeployGuard = PaymentCountryDeployGuard(properties)

    /** 큐 어댑터가 [PaymentTaskRunner.types] 마다 작업 처리기 하나를 등록하고 [PaymentTaskRunner.run] 을 부른다. */
    @Bean
    @ConditionalOnMissingBean
    fun paymentTaskRunner(handlers: List<PaymentTaskHandler>): PaymentTaskRunner = DefaultPaymentTaskRunner(handlers)

    /** 계정 탈퇴 고리 — 카드 두 칸을 지우고 주문 줄은 남긴다(스펙 §8.2). 플랫폼의 계약만 구현하므로 `account` 모듈이 없어도 된다(아무도 부르지 않을 뿐). */
    @Bean
    @ConditionalOnMissingBean(name = ["paymentAccountErasure"])
    fun paymentAccountErasure(store: PaymentLedgerStore, writer: PaymentLedgerWriter, properties: PaymentProperties, subscriptions: ObjectProvider<SubscriptionLifecycle>): AccountErasureListener =
        PaymentAccountErasure(store, writer, properties.erasure.accountId, subscriptions.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean(name = ["paymentAccountDataExporter"])
    fun paymentAccountDataExporter(store: PaymentLedgerStore, router: PaymentProviderRouter, writer: PaymentLedgerWriter, properties: PaymentProperties): AccountDataExporter =
        PaymentAccountDataExporter(PaymentQueries(store, router, writer, properties))
}

/** 판정기가 없는 조립의 기본 - 권한을 아무에게도 주지 않는다. */
private object NobodyIsExempt : OperatorAccess {
    override fun permissionsOf(ref: dev.sumin.skeleton.common.authz.OperatorRef): Set<String> = emptySet()
}
