package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * `payment-provider-subscription-cancel` - 결제사가 갱신하는 구독을 결제사에서 **즉시** 해지한다. 대상 id 는 `<결제사 id>:<결제사 구독 id>`.
 * 우리 장부에서 끝났거나 우리 줄이 받아들이지 않은 구독이 결제사에서 계속 과금되지 않게 한다. 결제사가 거절하면 다시 한다(이미 해지된 구독이면 결제사가 거절할 수 있어 끝내 죽으면 사람이 본다).
 */
class ProviderSubscriptionCancelTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The provider subscription cancel task")
        val provider = run.subjectId.substringBefore(':', "")
        val providerSubscriptionId = run.subjectId.substringAfter(':', "")
        if (provider.isEmpty() || providerSubscriptionId.isEmpty()) throw PaymentTaskPermanentException("The provider subscription cancel task has an unreadable subject")
        val gateway = try {
            router.gateway(provider, PaymentHostedSubscriptionGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentTaskRetryException("No payment provider is available to cancel a subscription of $provider")
        } catch (e: PaymentCapabilityUnsupportedException) {
            throw PaymentTaskPermanentException("The payment provider $provider cannot cancel subscriptions")
        }
        try {
            gateway.cancel(providerSubscriptionId, atPeriodEnd = false)
        } catch (e: Exception) {
            log.warn("canceling a provider subscription failed provider={} attempt={}/{} exception={} {}", provider, run.attempt, run.maxAttempts, e.javaClass.simpleName, PaymentFailures.describe(e))
            throw PaymentTaskRetryException("Canceling the provider subscription failed")
        }
    }

    override fun onDead(run: PaymentTaskRun) = Unit
}
