package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.PaymentBuyerCountryGateway
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentHostedSubscriptionGateway
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.PaymentTransactionGateway
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedPaymentEvent
import dev.sumin.skeleton.payment.TransactionRequest
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 서명된 웹훅이 곧 사실이고 거래를 서버가 먼저 만드는 결제사(Paddle 모양)의 대역. 웹훅 본문은 사건 id 한 줄이고, 사건은 [delivery] 가 등록한다. */
class FakePushGateway(override val providerId: String = "paddle", override val mode: PaymentMode = PaymentMode.TEST) :
    PaymentPushGateway, PaymentHostedSubscriptionGateway, PaymentTransactionGateway, PaymentCheckoutGateway, PaymentBuyerCountryGateway {
    private val events = ConcurrentHashMap<String, PushedPaymentEvent>()
    private val txnSeq = AtomicInteger()
    val cancelCalls = CopyOnWriteArrayList<Pair<String, Boolean>>()

    /** 서버가 만든 거래 - 요청 그대로. */
    val transactions = CopyOnWriteArrayList<Pair<String, TransactionRequest>>()

    @Volatile
    var cancelFails = false

    @Volatile
    var createFails = false

    /** 거래 만들기가 이 예외로 실패한다(`createFails` 보다 먼저) - 결제사가 돌려준 거절 모양을 시험에 심는다. */
    var createFailure: Throwable? = null

    /** 주소 id → 그 주소의 나라. */
    val buyerCountries = ConcurrentHashMap<String, String>()

    @Volatile
    var buyerCountryFails = false

    override fun buyerCountry(customerId: String, addressId: String): String? {
        check(!buyerCountryFails) { "planted address lookup failure" }
        return buyerCountries[addressId]
    }

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

    override fun interpret(payload: String): PushedPaymentEvent? = events[payload]

    /** 서버가 거래에 넣는 가격 id - 기본은 `pri_<코드>`. */
    override fun priceIdOf(itemRef: String): String? = "pri_$itemRef"

    override fun createTransaction(request: TransactionRequest): String {
        createFailure?.let { throw it }
        check(!createFails) { "planted Paddle transaction failure" }
        return "txn_s${txnSeq.incrementAndGet()}".also { transactions += it to request }
    }

    override fun checkout(order: CheckoutOrder): CheckoutInstruction =
        CheckoutInstruction(providerId, "PADDLE_CHECKOUT", true, order.orderId, order.orderName, order.amount, "token", mapOf("transactionId" to order.providerTransactionId.orEmpty()))

    override fun subscriptionSetup(request: HostedSubscriptionRequest): BillingSetup? =
        request.transactionId?.let { BillingSetup(providerId, "PADDLE_CHECKOUT", true, "token", request.customerKey, mapOf("transactionId" to it)) }

    override fun cancel(providerSubscriptionId: String, atPeriodEnd: Boolean) {
        check(!cancelFails) { "planted Paddle cancel failure" }
        cancelCalls += providerSubscriptionId to atPeriodEnd
    }
}
