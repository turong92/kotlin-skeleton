package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.crypto.TextEncryptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * 구독(스펙 `2026-10-09-subscription-design.md`) — **`skeleton.payment.subscription.enabled=true` 일 때만** 서고, 켰는데 앱이 [SubscriptionPlans] · 빌링 능력을 가진 결제사 · 빌링키를 암호화할
 * `crypto` 키([TextEncryptor])를 내지 않으면 **기동 실패**다(빠뜨린 채 뜬 구독은 카드를 받고도 과금할 수 없다).
 */
@AutoConfiguration(after = [PaymentLedgerAutoConfiguration::class])
@ConditionalOnBean(PaymentLedgerStore::class)
@ConditionalOnProperty(prefix = "skeleton.payment.subscription", name = ["enabled"], havingValue = "true")
class PaymentSubscriptionAutoConfiguration {
    /** 구독의 할인 일정(단계 복사 · 회차 · 금액) - 할인 수단이 없어도 서 있다(할인 지정이 없으면 아무 일도 하지 않는다). */
    @Bean
    @ConditionalOnMissingBean
    fun subscriptionDiscounts(store: PaymentLedgerStore, time: ObjectProvider<dev.sumin.skeleton.common.time.TimeProvider>): SubscriptionDiscounts {
        val clock = time.getIfAvailable { dev.sumin.skeleton.common.time.TimeProvider.systemUtc() }
        return SubscriptionDiscounts(store) { clock.now() }
    }

    @Bean
    @ConditionalOnMissingBean
    fun subscriptionLifecycle(tasks: PaymentTasks, writer: PaymentLedgerWriter, properties: PaymentProperties, review: PaymentReviewNotifier, discounts: SubscriptionDiscounts): SubscriptionLifecycle =
        SubscriptionLifecycle(tasks, writer, properties, review).also { it.discounts = discounts }

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
        projects: ObjectProvider<ProjectRegistry>,
        lifecycle: SubscriptionLifecycle,
        charges: SubscriptionCharges,
        tasks: PaymentTasks,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
        couponDiscounts: ObjectProvider<PaymentDiscounts>,
        scheduleDiscounts: SubscriptionDiscounts,
    ): PaymentSubscriptions {
        val plansBean = plans.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a SubscriptionPlans bean (the app owns the plans and prices)")
        val encryptorBean = encryptor.getIfAvailable()
            ?: error("skeleton.payment.subscription.enabled=true needs an encryption key to store billing keys: set skeleton.crypto.keys (the crypto module's TextEncryptor); with skeleton.projects give each project its own skeleton.projects.<id>.config.crypto.keys.<id> (projects must not share key bytes)")
        check(router.anyGateway(PaymentBillingGateway::class) || router.anyGateway(PaymentHostedSubscriptionGateway::class)) {
            "skeleton.payment.subscription.enabled=true needs a payment provider with the billing capability (PaymentBillingGateway, e.g. payment-toss) or one that renews by itself (PaymentHostedSubscriptionGateway, e.g. payment-paddle)"
        }
        // 프로젝트 모드: 빌링키를 받는 결제사를 켠 프로젝트는 빌링키를 암호화할 자기 키가 있어야 한다 — 카드를 받은 뒤에야 못 저장하는 일이 없게 기동 때 막는다
        projects.getIfAvailable()?.let { registry ->
            router.allRoutes().filter { routes -> routes.gateways.any { it is PaymentBillingGateway } }.forEach { routes ->
                val project = registry.active.single { it.id == routes.projectId }
                try {
                    ProjectContext.runIn(project) { encryptorBean.encrypt("probe") }
                } catch (ex: RuntimeException) {
                    throw IllegalStateException("project '${project.id}' has a billing payment provider but no usable crypto key for the billing keys (skeleton.projects.${project.id}.config.crypto.keys): ${ex.message}", ex)
                }
            }
        }
        return PaymentSubscriptions(store, router, plansBean, encryptorBean, lifecycle, charges, tasks, writer, properties, couponDiscounts = couponDiscounts.getIfAvailable(), scheduleDiscounts = scheduleDiscounts)
    }

    @Bean
    @ConditionalOnMissingBean
    fun subscriptionPlanChanges(
        store: PaymentLedgerStore,
        router: PaymentProviderRouter,
        plans: ObjectProvider<SubscriptionPlans>,
        encryptor: ObjectProvider<TextEncryptor>,
        lifecycle: SubscriptionLifecycle,
        charges: SubscriptionCharges,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
    ): SubscriptionPlanChanges = SubscriptionPlanChanges(
        store,
        router,
        plans.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a SubscriptionPlans bean"),
        encryptor.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a TextEncryptor (skeleton.crypto.keys; with skeleton.projects: skeleton.projects.<id>.config.crypto.keys.<id> for each project)"),
        lifecycle,
        charges,
        writer,
        properties,
    )

    /** 앱이 [SubscriptionRenewalGuard] 를 두지 않으면 늘 허용한다 — `payment` 는 `account` 를 모른다. */
    @Bean
    @ConditionalOnMissingBean
    fun subscriptionRenewalGuard(): SubscriptionRenewalGuard = SubscriptionRenewalGuard.ALLOW_ALL

    @Bean
    @ConditionalOnMissingBean
    fun subscriptionRenewTaskHandler(
        store: PaymentLedgerStore,
        router: PaymentProviderRouter,
        plans: ObjectProvider<SubscriptionPlans>,
        encryptor: ObjectProvider<TextEncryptor>,
        lifecycle: SubscriptionLifecycle,
        charges: SubscriptionCharges,
        guard: SubscriptionRenewalGuard,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
        discounts: SubscriptionDiscounts,
    ): SubscriptionRenewTaskHandler = SubscriptionRenewTaskHandler(
        store,
        router,
        plans.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a SubscriptionPlans bean"),
        encryptor.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a TextEncryptor (skeleton.crypto.keys; with skeleton.projects: skeleton.projects.<id>.config.crypto.keys.<id> for each project)"),
        lifecycle,
        charges,
        guard,
        writer,
        properties.subscription.renewal.retryInterval,
        discounts,
    )

    @Bean
    @ConditionalOnMissingBean
    fun providerSubscriptionCancelTaskHandler(store: PaymentLedgerStore, router: PaymentProviderRouter): ProviderSubscriptionCancelTaskHandler = ProviderSubscriptionCancelTaskHandler(store, router)

    @Bean
    @ConditionalOnMissingBean
    fun subscriptionRevokeTaskHandler(store: PaymentLedgerStore, router: PaymentProviderRouter, encryptor: ObjectProvider<TextEncryptor>, writer: PaymentLedgerWriter): SubscriptionRevokeTaskHandler =
        SubscriptionRevokeTaskHandler(store, router, encryptor.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a TextEncryptor (skeleton.crypto.keys; with skeleton.projects: skeleton.projects.<id>.config.crypto.keys.<id> for each project)"), writer)

    /** 앱이 [SubscriptionUsageCheck] 를 두지 않으면 「안 썼다」 - 7일 철회가 시간만으로 판단된다. */
    @Bean
    @ConditionalOnMissingBean
    fun subscriptionUsageCheck(): SubscriptionUsageCheck = SubscriptionUsageCheck.NEVER_USED

    /** 내장 `period-end-only` · `korea` 에 앱이 빈으로 더한 정책을 합친다(같은 이름이면 앱의 것이 이긴다). 나라 → 정책 지도는 `skeleton.payment.subscription.cancel.policies`. */
    @Bean
    @ConditionalOnMissingBean
    fun subscriptionCancelPolicies(custom: ObjectProvider<SubscriptionCancelPolicy>, properties: PaymentProperties): SubscriptionCancelPolicies {
        val cancel = properties.subscription.cancel
        val builtIn = KoreaConsumerCancelPolicy(cancel.korea.penaltyPercent, cancel.korea.withdrawalDays)
        return SubscriptionCancelPolicies(listOf<SubscriptionCancelPolicy>(builtIn) + custom.orderedStream().toList(), cancel.policies)
    }

    @Bean
    @ConditionalOnMissingBean
    fun subscriptionCancellations(
        store: PaymentLedgerStore,
        plans: ObjectProvider<SubscriptionPlans>,
        policies: SubscriptionCancelPolicies,
        usage: SubscriptionUsageCheck,
        lifecycle: SubscriptionLifecycle,
        refunds: PaymentRefunds,
        convergence: PaymentConvergence,
        tasks: PaymentTasks,
        writer: PaymentLedgerWriter,
        properties: PaymentProperties,
    ): SubscriptionCancellations = SubscriptionCancellations(
        store,
        plans.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a SubscriptionPlans bean"),
        policies,
        usage,
        lifecycle,
        refunds,
        convergence,
        tasks,
        writer,
        properties,
    )

    @Bean
    @ConditionalOnMissingBean
    fun subscriptionCancelRefundTaskHandler(store: PaymentLedgerStore, cancellations: SubscriptionCancellations): SubscriptionCancelRefundTaskHandler =
        SubscriptionCancelRefundTaskHandler(store, cancellations)
}
