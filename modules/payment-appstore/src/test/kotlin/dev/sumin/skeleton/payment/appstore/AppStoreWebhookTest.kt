package dev.sumin.skeleton.payment.appstore

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedItem
import dev.sumin.skeleton.payment.PushedPayment
import dev.sumin.skeleton.payment.PushedPaymentEvent
import dev.sumin.skeleton.payment.PushedRefund
import dev.sumin.skeleton.payment.PushedRefundKind
import dev.sumin.skeleton.payment.PushedRefundStatus
import dev.sumin.skeleton.payment.PushedSubscription
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.WebhookDelivery
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AppStoreWebhookTest {
    private val chain = TestChain.create()
    private val gateway = AppStorePaymentProvider(httpClient(), properties(), verifier(chain), { NOW })
    private val sub = transactionJson(
        transactionId = "2000000000000011", originalTransactionId = "2000000000000010", productId = "com.example.sample.premium.monthly", type = "Auto-Renewable Subscription",
        price = 9_900_000, purchaseDate = NOW.minusSeconds(60), expiresDate = NOW.plus(Duration.ofDays(30)),
    )

    private fun delivery(body: String) = WebhookDelivery(emptyMap(), body.toByteArray())

    private fun event(type: String, subtype: String? = null, transaction: String? = sub, renewal: String? = renewalJson(), environment: String = "Production"): PushedPaymentEvent {
        val envelope = gateway.parse(delivery(notificationBody(chain, type, subtype, transaction = transaction, renewal = renewal, environment = environment)))
        assertThat(envelope.resourceType).`as`("$type/$subtype is handled").isEqualTo(PushedEvents.RESOURCE_TYPE)
        return gateway.interpret(envelope.storedPayload!!)!!
    }

    // --- 위조 · 거절 (검증 전에는 아무것도 받아들이지 않는다) ---

    @Test
    fun `a notification signed under another root is rejected`() {
        val forged = notificationBody(chain, "REFUND", payloadSigner = TestChain.create())
        assertThatThrownBy { gateway.parse(delivery(forged)) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
    }

    @Test
    fun `a signed notification whose inner transaction is signed under another root is rejected`() {
        val forged = notificationBody(chain, "REFUND", transactionSigner = TestChain.create())
        assertThatThrownBy { gateway.parse(delivery(forged)) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
    }

    @Test
    fun `a notification for another app is rejected`() {
        assertThatThrownBy { gateway.parse(delivery(notificationBody(chain, "REFUND", bundleId = "com.example.other"))) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
        assertThatThrownBy { gateway.parse(delivery(notificationBody(chain, "REFUND", transaction = transactionJson(bundleId = "com.example.other")))) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
    }

    @Test
    fun `a production notification for another app id is rejected but a sandbox one has none to check`() {
        assertThatThrownBy { gateway.parse(delivery(notificationBody(chain, "REFUND", appAppleId = "999")) ) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
        assertThat(gateway.parse(delivery(notificationBody(chain, "REFUND", environment = "Sandbox", transaction = transactionJson(environment = "Sandbox")))).resourceType).isEqualTo(PushedEvents.RESOURCE_TYPE)
    }

    @Test
    fun `bodies that carry no signed payload are rejected`() {
        for (body in listOf("", "not json", "{}", """{"signedPayload":""}""", """{"signedPayload":"a.b.c"}""", """{"signedPayload":5}""")) {
            assertThatThrownBy { gateway.parse(delivery(body)) }.`as`(body).isInstanceOf(PaymentWebhookRejectedException::class.java)
        }
    }

    // --- 봉투 ---

    @Test
    fun `the notification uuid is the event key and the same delivery twice is the same envelope`() {
        val body = notificationBody(chain, "REFUND", uuid = "11111111-aaaa-4bbb-8ccc-0000000000ff")
        val first = gateway.parse(delivery(body))
        val second = gateway.parse(delivery(body))
        assertThat(first.eventKey).isEqualTo("11111111-aaaa-4bbb-8ccc-0000000000ff")
        assertThat(first.eventType).isEqualTo("REFUND")
        assertThat(first.resourceId).isEqualTo("2000000000000001")
        assertThat(second).isEqualTo(first)
        assertThat(gateway.signed).isTrue()
    }

    @Test
    fun `the test notification and kinds the ledger does not handle are unsupported and stored as nothing`() {
        val test = gateway.parse(delivery(notificationBody(chain, "TEST", transaction = null)))
        assertThat(test.resourceType).isEqualTo("UNSUPPORTED")
        assertThat(test.storedPayload).isNull()
        for (type in listOf("CONSUMPTION_REQUEST", "PRICE_INCREASE", "REFUND_DECLINED", "OFFER_REDEEMED", "ONE_TIME_CHARGE", "EXTERNAL_PURCHASE_TOKEN")) {
            assertThat(gateway.parse(delivery(notificationBody(chain, type))).resourceType).`as`(type).isEqualTo("UNSUPPORTED")
        }
    }

    // --- 해석 (§2.3 표) ---

    @Test
    fun `a refund becomes a refund event for the whole amount of the transaction`() {
        val refunded = transactionJson(revocationDate = NOW, revocationType = "REFUND_FULL", revocationPercentage = 100_000)
        val refund = event("REFUND", transaction = refunded, renewal = null) as PushedRefund
        assertThat(refund.eventId).isEqualTo("11111111-aaaa-4bbb-8ccc-000000000001")
        assertThat(refund.occurredAt).isEqualTo(NOW)
        assertThat(refund.kind).isEqualTo(PushedRefundKind.REFUND)
        assertThat(refund.status).isEqualTo(PushedRefundStatus.APPROVED)
        assertThat(refund.providerPaymentId).isEqualTo("2000000000000001")
        assertThat(refund.providerRefundId).isNotBlank()
        assertThat(refund.amount).isEqualTo(PaymentAmount(4990, "KRW"))
        assertThat(refund.mode).isEqualTo(PaymentMode.LIVE)
    }

    @Test
    fun `a prorated refund is the share Apple names`() {
        val refunded = transactionJson(price = 10_000_000, revocationDate = NOW, revocationType = "REFUND_PRORATED", revocationPercentage = 25_000)
        val refund = event("REFUND", transaction = refunded, renewal = null) as PushedRefund
        assertThat(refund.amount).isEqualTo(PaymentAmount(2500, "KRW"))
    }

    @Test
    fun `a sandbox refund is a test event`() {
        val refunded = transactionJson(environment = "Sandbox", revocationDate = NOW, revocationType = "REFUND_FULL")
        assertThat((event("REFUND", transaction = refunded, renewal = null, environment = "Sandbox") as PushedRefund).mode).isEqualTo(PaymentMode.TEST)
    }

    @Test
    fun `a refund of a subscription charge names the subscription`() {
        val refund = event("REFUND", transaction = sub.replace("}", ",\"revocationDate\":${NOW.toEpochMilli()},\"revocationType\":\"REFUND_FULL\"}")) as PushedRefund
        assertThat(refund.providerSubscriptionId).isEqualTo("2000000000000010")
        assertThat(refund.providerPaymentId).isEqualTo("2000000000000011")
    }

    @Test
    fun `a reversed refund reopens the same refund line`() {
        val reversed = event("REFUND_REVERSED", transaction = transactionJson(), renewal = null) as PushedRefund
        val refund = event("REFUND", transaction = transactionJson(revocationDate = NOW, revocationType = "REFUND_FULL"), renewal = null) as PushedRefund
        assertThat(reversed.status).isEqualTo(PushedRefundStatus.REVERSED)
        assertThat(reversed.providerRefundId).isEqualTo(refund.providerRefundId)
        assertThat(reversed.providerPaymentId).isEqualTo(refund.providerPaymentId)
    }

    @Test
    fun `a renewal is a payment on the subscription for the new period`() {
        val renewal = event("DID_RENEW") as PushedPayment
        assertThat(renewal.providerPaymentId).isEqualTo("2000000000000011")
        assertThat(renewal.providerSubscriptionId).isEqualTo("2000000000000010")
        assertThat(renewal.total).isEqualTo(PaymentAmount(9900, "KRW"))
        assertThat(renewal.periodStart).isEqualTo(NOW.minusSeconds(60))
        assertThat(renewal.periodEnd).isEqualTo(NOW.plus(Duration.ofDays(30)))
        assertThat(renewal.items).containsExactly(PushedItem("com.example.sample.premium.monthly", 1))
        assertThat(renewal.mode).isEqualTo(PaymentMode.LIVE)
        assertThat(renewal.ourOrderId).isNull()
    }

    @Test
    fun `subscription state notifications map to the ledger states`() {
        fun status(type: String, subtype: String? = null, renewal: String = renewalJson()) = (event(type, subtype, renewal = renewal) as PushedSubscription).status
        assertThat(status("DID_FAIL_TO_RENEW", "GRACE_PERIOD")).isEqualTo(PushedSubscriptionStatus.ACTIVE)
        assertThat(status("DID_FAIL_TO_RENEW")).isEqualTo(PushedSubscriptionStatus.PAST_DUE)
        assertThat(status("GRACE_PERIOD_EXPIRED")).isEqualTo(PushedSubscriptionStatus.PAST_DUE)
        assertThat(status("DID_CHANGE_RENEWAL_STATUS", "AUTO_RENEW_DISABLED", renewalJson(autoRenewStatus = 0))).isEqualTo(PushedSubscriptionStatus.CANCELING)
        assertThat(status("DID_CHANGE_RENEWAL_STATUS", "AUTO_RENEW_ENABLED")).isEqualTo(PushedSubscriptionStatus.ACTIVE)
        assertThat(status("EXPIRED", "VOLUNTARY")).isEqualTo(PushedSubscriptionStatus.ENDED)
        assertThat(status("REVOKE")).isEqualTo(PushedSubscriptionStatus.ENDED)
    }

    @Test
    fun `a subscription event keeps the subscription id, the period and the product`() {
        val state = event("EXPIRED", "VOLUNTARY") as PushedSubscription
        assertThat(state.providerSubscriptionId).isEqualTo("2000000000000010")
        assertThat(state.periodEnd).isEqualTo(NOW.plus(Duration.ofDays(30)))
        assertThat(state.items).containsExactly(PushedItem("com.example.sample.premium.monthly", 1))
        assertThat(state.mode).isEqualTo(PaymentMode.LIVE)
    }

    @Test
    fun `an upgrade follows the new product and a downgrade waits for the next renewal`() {
        val up = event("DID_CHANGE_RENEWAL_PREF", "UPGRADE", transaction = sub.replace("premium.monthly", "premium.yearly")) as PushedSubscription
        assertThat(up.items).containsExactly(PushedItem("com.example.sample.premium.yearly", 1))
        assertThat(gateway.parse(delivery(notificationBody(chain, "DID_CHANGE_RENEWAL_PREF", "DOWNGRADE", transaction = sub))).resourceType).isEqualTo("UNSUPPORTED")
    }

    @Test
    fun `the grace period end is the period end while the grace lasts`() {
        val grace = NOW.plus(Duration.ofDays(16))
        val state = event("DID_FAIL_TO_RENEW", "GRACE_PERIOD", renewal = renewalJson(gracePeriodExpiresDate = grace, isInBillingRetryPeriod = true)) as PushedSubscription
        assertThat(state.periodEnd).isEqualTo(grace)
    }
}
