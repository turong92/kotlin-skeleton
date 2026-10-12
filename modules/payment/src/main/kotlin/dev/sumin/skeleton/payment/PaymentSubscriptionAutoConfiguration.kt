package dev.sumin.skeleton.payment

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
        encryptor.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a TextEncryptor (skeleton.crypto.keys)"),
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
    ): SubscriptionRenewTaskHandler = SubscriptionRenewTaskHandler(
        store,
        router,
        plans.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a SubscriptionPlans bean"),
        encryptor.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a TextEncryptor (skeleton.crypto.keys)"),
        lifecycle,
        charges,
        guard,
        writer,
        properties.subscription.renewal.retryInterval,
    )

    @Bean
    @ConditionalOnMissingBean
    fun subscriptionRevokeTaskHandler(store: PaymentLedgerStore, router: PaymentProviderRouter, encryptor: ObjectProvider<TextEncryptor>, writer: PaymentLedgerWriter): SubscriptionRevokeTaskHandler =
        SubscriptionRevokeTaskHandler(store, router, encryptor.getIfAvailable() ?: error("skeleton.payment.subscription.enabled=true needs a TextEncryptor (skeleton.crypto.keys)"), writer)

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
