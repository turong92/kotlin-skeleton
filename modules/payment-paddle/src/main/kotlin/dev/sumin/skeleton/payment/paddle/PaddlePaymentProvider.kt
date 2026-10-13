package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentHostedSubscriptionGateway
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.PaymentTransactionGateway
import dev.sumin.skeleton.payment.TransactionRequest
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedItem
import dev.sumin.skeleton.payment.PushedPayment
import dev.sumin.skeleton.payment.PushedPaymentEvent
import dev.sumin.skeleton.payment.PushedSubscription
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookEnvelope
import java.time.Instant
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Paddle(판매 대행) — 결제창은 프론트의 Paddle.js, 결제 · 갱신은 Paddle 이 하고 우리는 **서명된 웹훅**으로 결과를 받는다. 서버가 Paddle API 를 부르는 것은 구독 해지뿐이다.
 * 결제 · 환불을 서버가 시키는 능력(승인 · 환불 · 조회)은 없다. 웹훅 본문의 금액 · 통화가 그대로 장부에 간다.
 */
class PaddlePaymentProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: PaddleProperties,
    private val time: TimeProvider = TimeProvider.systemUtc(),
) : PaymentPushGateway, PaymentCheckoutGateway, PaymentHostedSubscriptionGateway, PaymentTransactionGateway {
    override val providerId: String = properties.providerId
    override val supportedCurrencies: Set<String> = properties.supportedCurrencies
    override val supportedCountries: Set<String> = properties.supportedCountries
    override val mode: PaymentMode = if (properties.sandbox) PaymentMode.TEST else PaymentMode.LIVE
    override val signed: Boolean = true

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    init {
        require(providerId.isNotBlank()) { "Paddle providerId must not be blank" }
    }

    // --- 웹훅 ---

    override fun parse(delivery: WebhookDelivery): WebhookEnvelope {
        if (!PaddleSignature.verify(delivery.headers["paddle-signature"], delivery.body, properties.webhookSecret, time.now(), properties.signatureTolerance)) {
            throw PaymentWebhookRejectedException()
        }
        val root = read(delivery.body.decodeToString()) ?: throw PaymentWebhookRejectedException()
        val eventId = root.text("event_id") ?: throw PaymentWebhookRejectedException()
        val type = root.text("event_type") ?: throw PaymentWebhookRejectedException()
        val resourceId = root["data"]?.text("id")
        if (type !in HANDLED || resourceId == null) {
            return WebhookEnvelope(eventKey = eventId, eventType = type, resourceType = "UNSUPPORTED", resourceId = resourceId ?: eventId)
        }
        return WebhookEnvelope(eventKey = eventId, eventType = type, resourceType = PushedEvents.RESOURCE_TYPE, resourceId = resourceId, storedPayload = delivery.body.decodeToString())
    }

    override fun interpret(payload: String): PushedPaymentEvent? {
        val root = read(payload) ?: error("unreadable Paddle payload")
        val data = root["data"] ?: error("Paddle payload without data")
        val eventId = root.text("event_id") ?: error("Paddle payload without event_id")
        val occurredAt = Instant.parse(root.text("occurred_at") ?: error("Paddle payload without occurred_at"))
        val custom = data["custom_data"]
        return when (val type = root.text("event_type")) {
            "transaction.completed" -> payment(eventId, occurredAt, data, custom)
            in SUBSCRIPTION_EVENTS -> subscription(eventId, occurredAt, data, custom, type)
            else -> null
        }
    }

    private fun payment(eventId: String, occurredAt: Instant, data: JsonNode, custom: JsonNode?): PushedPayment {
        val totals = data["details"]?.get("totals")
        val currency = (data.text("currency_code") ?: totals?.text("currency_code")) ?: error("Paddle transaction without a currency")
        val total = totals?.text("total")?.toLongOrNull() ?: error("Paddle transaction without a total")
        val payments = data["payments"]?.toList().orEmpty()
        val chosen = payments.firstOrNull { it.text("status") == "captured" } ?: payments.firstOrNull()
        val details = chosen?.get("method_details")
        val card = details?.get("card")?.let { PaymentCard(it.text("type")?.uppercase(), it.text("last4")) }
        return PushedPayment(
            eventId = eventId,
            occurredAt = occurredAt,
            providerPaymentId = data.text("id") ?: error("Paddle transaction without id"),
            total = PaymentAmount(total, currency),
            tax = totals.text("tax")?.toLongOrNull(),
            paidAt = data.text("billed_at")?.let(Instant::parse) ?: occurredAt,
            ourOrderId = custom.text(PushedEvents.ORDER_KEY),
            ourAccountId = custom.text(PushedEvents.ACCOUNT_KEY),
            ourSubscriptionId = custom.text(PushedEvents.SUBSCRIPTION_KEY),
            providerSubscriptionId = data.text("subscription_id"),
            method = details.text("type")?.uppercase()?.replace('-', '_'),
            card = card,
            periodStart = data["billing_period"]?.text("starts_at")?.let(Instant::parse),
            periodEnd = data["billing_period"]?.text("ends_at")?.let(Instant::parse),
            items = data["items"]?.toList().orEmpty().mapNotNull { item ->
                val priceId = (item["price"]?.text("id") ?: item.text("price_id")) ?: return@mapNotNull null
                PushedItem(priceId, item["quantity"]?.asInt(1) ?: 1)
            },
        )
    }

    override fun priceIdOf(itemRef: String): String? = properties.prices[itemRef]?.takeIf { it.isNotBlank() }

    private fun subscription(eventId: String, occurredAt: Instant, data: JsonNode, custom: JsonNode?, type: String?): PushedSubscription? {
        val scheduledCancel = data["scheduled_change"]?.text("action") == "cancel"
        val status = when (data.text("status")) {
            "active" -> if (scheduledCancel) PushedSubscriptionStatus.CANCELING else PushedSubscriptionStatus.ACTIVE
            "past_due" -> PushedSubscriptionStatus.PAST_DUE
            "canceled" -> PushedSubscriptionStatus.ENDED
            else -> return null // paused · trialing 은 1차에서 다루지 않는다
        }
        return PushedSubscription(
            eventId = eventId,
            occurredAt = occurredAt,
            providerSubscriptionId = data.text("id") ?: error("Paddle subscription without id for $type"),
            status = status,
            ourAccountId = custom.text(PushedEvents.ACCOUNT_KEY),
            ourSubscriptionId = custom.text(PushedEvents.SUBSCRIPTION_KEY),
            periodStart = data["current_billing_period"]?.text("starts_at")?.let(Instant::parse),
            periodEnd = data["current_billing_period"]?.text("ends_at")?.let(Instant::parse),
            transactionId = data.text("transaction_id"),
        )
    }

    // --- 결제창 ---

    override fun canOpenCheckout(): Boolean = properties.clientToken.isNotBlank()

    /** 거래를 서버가 먼저 만든다 - 품목(가격 · 수량)과 `custom_data`(우리 줄 id)는 서버가 정한 값이다. 가격이 설정에 없으면 던진다. */
    override fun createTransaction(request: TransactionRequest): String {
        val price = priceIdOf(request.itemRef) ?: error("no Paddle price is configured for ${request.itemRef}")
        val response = requireNotNull(
            httpClient.post(
                clientName = properties.clientName,
                path = "/transactions",
                body = mapOf("items" to listOf(mapOf("price_id" to price, "quantity" to request.quantity)), "custom_data" to request.customData),
                responseType = String::class.java,
            ) {
                baseUrl(properties.resolvedBaseUrl)
                header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
                timeout(properties.createTransactionTimeout)
                loggingTag("payment.paddle.create-transaction")
            }.block(),
        )
        return read(response)?.get("data")?.text("id")?.takeIf { it.isNotBlank() } ?: error("Paddle did not return a transaction id")
    }

    /** 결제창은 서버가 만든 거래 id 만 연다 - 가격 · customData 를 브라우저가 넘기지 않는다. */
    override fun checkout(order: CheckoutOrder): CheckoutInstruction =
        CheckoutInstruction(
            provider = providerId,
            kind = KIND,
            testMode = properties.sandbox,
            orderId = order.orderId,
            orderName = order.orderName,
            amount = order.amount,
            clientKey = properties.clientToken,
            attributes = mapOf("environment" to properties.environment.trim().lowercase(), "transactionId" to order.providerTransactionId.orEmpty()),
        )

    override fun subscriptionSetup(request: HostedSubscriptionRequest): BillingSetup? {
        val transactionId = request.transactionId ?: return null
        if (!canOpenCheckout()) return null
        return BillingSetup(
            provider = providerId,
            kind = KIND,
            testMode = properties.sandbox,
            clientKey = properties.clientToken,
            customerKey = request.customerKey,
            attributes = mapOf("environment" to properties.environment.trim().lowercase(), "transactionId" to transactionId),
        )
    }

    /** 거절은 그대로 던진다(이미 해지된 구독의 응답 모양은 샌드박스에서 잰 뒤 다룬다). */
    override fun cancel(providerSubscriptionId: String, atPeriodEnd: Boolean) {
        requireNotNull(
            httpClient.post(
                clientName = properties.clientName,
                path = "/subscriptions/{id}/cancel",
                body = mapOf("effective_from" to if (atPeriodEnd) "next_billing_period" else "immediately"),
                responseType = String::class.java,
            ) {
                baseUrl(properties.resolvedBaseUrl)
                uriVariable("id", providerSubscriptionId)
                header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
                loggingTag("payment.paddle.cancel")
            }.block(),
        )
    }

    private fun read(text: String): JsonNode? = try {
        mapper.readTree(text)?.takeIf { it.isObject }
    } catch (_: Exception) {
        null
    }

    private fun JsonNode?.text(name: String): String? = this?.get(name)?.takeIf { it.isString || it.isNumber }?.asString()?.takeIf { it.isNotBlank() }

    private companion object {
        const val KIND = "PADDLE_CHECKOUT"
        val SUBSCRIPTION_EVENTS = setOf("subscription.created", "subscription.activated", "subscription.updated", "subscription.canceled", "subscription.past_due")
        val HANDLED = SUBSCRIPTION_EVENTS + "transaction.completed"
    }
}
