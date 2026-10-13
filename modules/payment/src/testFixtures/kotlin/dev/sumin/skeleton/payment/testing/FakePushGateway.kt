package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentHostedSubscriptionGateway
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedPaymentEvent
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 서명된 웹훅이 곧 사실인 결제사(Paddle 모양)의 대역. 웹훅 본문은 사건 id 한 줄이고, 사건은 [delivery] 가 등록한다. */
class FakePushGateway(override val providerId: String = "paddle", override val mode: PaymentMode = PaymentMode.TEST) : PaymentPushGateway, PaymentHostedSubscriptionGateway {
    private val events = ConcurrentHashMap<String, PushedPaymentEvent>()
    val cancelCalls = CopyOnWriteArrayList<Pair<String, Boolean>>()

    @Volatile
    var cancelFails = false

    override val signed: Boolean = true

    fun delivery(event: PushedPaymentEvent, signature: String = "ok"): WebhookDelivery {
        events[event.eventId] = event
        return WebhookDelivery(mapOf("x-sig" to signature), event.eventId.toByteArray())
    }

    override fun parse(delivery: WebhookDelivery): WebhookEnvelope {
        if (delivery.headers["x-sig"] != "ok") throw PaymentWebhookRejectedException()
        val id = String(delivery.body)
        return WebhookEnvelope(eventKey = id, eventType = "test.event", resourceType = PushedEvents.RESOURCE_TYPE, resourceId = "res_$id", storedPayload = id)
    }

    /** 상품/플랜 코드 → 가격 id. 기본은 `pri_<코드>`. */
    val prices = ConcurrentHashMap<String, String>()

    override fun priceIdOf(itemRef: String): String? = prices[itemRef] ?: "pri_$itemRef"

    override fun interpret(payload: String): PushedPaymentEvent? = events[payload]

    override fun subscriptionSetup(request: HostedSubscriptionRequest): BillingSetup? =
        BillingSetup(providerId, "paddle", true, "token", request.customerKey, mapOf("priceId" to "pri_${request.planCode}", "customData" to "{}"))

    override fun cancel(providerSubscriptionId: String, atPeriodEnd: Boolean) {
        check(!cancelFails) { "planted Paddle cancel failure" }
        cancelCalls += providerSubscriptionId to atPeriodEnd
    }
}
