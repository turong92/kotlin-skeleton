package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedPayment
import dev.sumin.skeleton.payment.PushedPaymentFailure
import dev.sumin.skeleton.payment.PushedSubscription
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.WebhookDelivery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PaddleWebhookTest {
    private val gateway = gateway()

    private fun delivery(body: String, header: String = sign(body)) = WebhookDelivery(mapOf("Paddle-Signature" to header), body.toByteArray())

    @Test
    fun `a forged signature is rejected`() {
        val body = transactionCompleted()
        assertThatThrownBy { gateway.parse(delivery(body, sign(body, secret = "other"))) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
        assertThatThrownBy { gateway.parse(delivery(body + " ", sign(body))) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
        assertThatThrownBy { gateway.parse(WebhookDelivery(emptyMap(), body.toByteArray())) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
    }

    @Test
    fun `an old timestamp is rejected even with a valid signature`() {
        val body = transactionCompleted()
        val old = NOW.epochSecond - 3600
        assertThatThrownBy { gateway.parse(delivery(body, sign(body, ts = old))) }.isInstanceOf(PaymentWebhookRejectedException::class.java)
    }

    @Test
    fun `any of several h1 values may match during secret rotation`() {
        val body = transactionCompleted()
        val good = sign(body)
        assertThat(gateway.parse(delivery(body, "$good;h1=${"0".repeat(64)}")).resourceType).isEqualTo(PushedEvents.RESOURCE_TYPE)
    }

    @Test
    fun `a signed payment event becomes a pushed envelope that keeps the verified body`() {
        val body = transactionCompleted()
        val envelope = gateway.parse(delivery(body))
        assertThat(envelope.eventKey).isEqualTo("evt_1")
        assertThat(envelope.resourceId).isEqualTo("txn_1")
        assertThat(envelope.storedPayload).isEqualTo(body)
    }

    @Test
    fun `events the ledger does not handle are unsupported`() {
        val body = """{"event_id":"evt_9","event_type":"customer.created","occurred_at":"2026-10-13T09:59:59Z","data":{"id":"ctm_1"}}"""
        assertThat(gateway.parse(delivery(body)).resourceType).isEqualTo("UNSUPPORTED")
    }

    @Test
    fun `a failed payment attempt is handled and carries Paddle's reason code and no body text`() {
        val body = paymentFailed()
        assertThat(gateway.parse(delivery(body)).resourceType).isEqualTo(PushedEvents.RESOURCE_TYPE)
        val event = gateway.interpret(body) as PushedPaymentFailure
        assertThat(event.providerPaymentId).isEqualTo("txn_1")
        assertThat(event.errorCode).isEqualTo("declined")
        assertThat(event.ourOrderId).isEqualTo("ord_1")
    }

    @Test
    fun `the cycle comes from Paddle's price - on a payment and on a subscription event`() {
        assertThat((gateway.interpret(transactionCompleted()) as PushedPayment).period).isEqualTo("P1M")
        assertThat((gateway.interpret(subscriptionEvent()) as PushedSubscription).period).isEqualTo("P1M")
    }

    @Test
    fun `a payment keeps the currency and amount Paddle sent and our ids from custom data`() {
        val event = gateway.interpret(transactionCompleted(total = "1500", currency = "EUR")) as PushedPayment
        assertThat(event.total).isEqualTo(PaymentAmount(1500, "EUR"))
        assertThat(event.tax).isEqualTo(90)
        assertThat(event.ourOrderId).isEqualTo("ord_1")
        assertThat(event.ourAccountId).isEqualTo("acc_1")
        assertThat(event.card?.last4).isEqualTo("4242")
        assertThat(event.customerId).isEqualTo("ctm_1")
        assertThat(event.addressId).isEqualTo("add_1")
        assertThat(event.items).containsExactly(dev.sumin.skeleton.payment.PushedItem("pri_cheer", 1))
    }

    @Test
    fun `a scheduled cancel on an active subscription is CANCELING and canceled is ENDED`() {
        val scheduled = gateway.interpret(subscriptionEvent(scheduled = """{"action":"cancel","effective_at":"2026-11-13T09:59:00Z"}""")) as PushedSubscription
        assertThat(scheduled.status).isEqualTo(PushedSubscriptionStatus.CANCELING)
        assertThat((gateway.interpret(subscriptionEvent("subscription.canceled", "canceled")) as PushedSubscription).status).isEqualTo(PushedSubscriptionStatus.ENDED)
        assertThat((gateway.interpret(subscriptionEvent("subscription.past_due", "past_due")) as PushedSubscription).status).isEqualTo(PushedSubscriptionStatus.PAST_DUE)
        val active = gateway.interpret(subscriptionEvent()) as PushedSubscription
        assertThat(active.ourSubscriptionId).isEqualTo("sub_ours")
        assertThat(active.periodEnd).isNotNull()
        assertThat((gateway.interpret(subscriptionEvent("subscription.created", transactionId = "txn_first")) as PushedSubscription).transactionId).isEqualTo("txn_first")
    }

    @Test
    fun `a subscription update transaction is read as a change payment with the credit it used`() {
        val change = transactionCompleted(subscriptionId = "sub_1").replace(""""origin":"web"""", """"origin":"subscription_update"""").replace(""""total":"990"""", """"total":"990","credit":"300"""")
        val event = gateway.interpret(change) as PushedPayment
        assertThat(event.subscriptionChange).isTrue()
        assertThat(event.credit).isEqualTo(300)

        val plain = gateway.interpret(transactionCompleted(subscriptionId = "sub_1")) as PushedPayment
        assertThat(plain.subscriptionChange).isFalse()
        assertThat(plain.credit).isNull()
        assertThat((gateway.interpret(change.replace(""""credit":"300"""", """"credit":"0"""")) as PushedPayment).credit).isNull()
    }

    @Test
    fun `a subscription updated event carries its items and a price id maps back to the plan code, links first`() {
        val body = subscriptionEvent().replace(""""scheduled_change"""", """"items":[{"price":{"id":"pri_basic"},"quantity":1}],"scheduled_change"""")
        assertThat((gateway.interpret(body) as PushedSubscription).items).containsExactly(dev.sumin.skeleton.payment.PushedItem("pri_basic", 1))
        assertThat((gateway.interpret(subscriptionEvent()) as PushedSubscription).items).isEmpty()

        assertThat(gateway.itemRefOf("pri_basic")).isEqualTo("basic")
        assertThat(gateway.itemRefOf("pri_nobody")).isNull()
        val linked = gateway(refs = object : dev.sumin.skeleton.payment.ProviderPriceRefs {
            override fun priceIdOf(provider: String, itemRef: String) = mapOf("plus" to "pri_basic")[itemRef]
            override fun all(provider: String) = mapOf("plus" to "pri_basic")
        })
        assertThat(linked.itemRefOf("pri_basic")).isEqualTo("plus")
    }
}
