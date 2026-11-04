package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.PaymentStoreGateway
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
import dev.sumin.skeleton.payment.StoreClaim
import dev.sumin.skeleton.payment.StorePurchase
import dev.sumin.skeleton.payment.StorePurchaseKind
import dev.sumin.skeleton.payment.StorePurchaseState
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookEnvelope
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Currency
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Google Play(앱 안 결제) — 구매는 이미 스토어 안에서 끝났다. 서버는 앱이 보낸 구매 토큰을 믿지 않고 **토큰으로 Google Play Developer API 에 직접 묻고**([fetch]: 단건 `productsv2`,
 * 구독 `subscriptionsv2`, 금액 `orders.get`) 구글이 답한 것만 믿는다. 갱신 · 만료 · 환불은 **RTDN(Pub/Sub push)** 으로 받되, RTDN 은 신호일 뿐이라 **토큰으로 API 를 다시 읽어**
 * 지금 사실로 사건을 만든다([parse] 는 push 의 OIDC 토큰을 확인하고 신호만 저장, [interpret] 은 락 밖에서 API 를 부른다 - 일시 실패는 던져 수신함 줄이 다시 시도된다).
 *
 * **모드는 거래마다 다르다**: 이 결제사의 [mode] 는 `LIVE` 이고, 시험 구매(라이선스 테스터)는 거래 · 사건의 모드가 `TEST` 라 대시보드 매출에서 빠진다.
 * 금액은 `orders.get` 의 `total` 이다 - 운영(`LIVE`)에서 못 읽으면 던진다(공짜로 지급하지 않는다). 시험 구매에 주문이 없으면 0 원 · 통화 `XXX` · 결제 번호 `pt:` + `sha256(토큰)`.
 * 구독 번호는 `sha256(구매 토큰)` 16진수다(구글에는 애플의 `originalTransactionId` 같은 안 바뀌는 번호가 없다).
 */
class GooglePlayPaymentProvider internal constructor(
    private val httpClient: ExternalHttpClient,
    private val properties: GooglePlayProperties,
    private val time: TimeProvider,
    credentials: GooglePlayCredentials,
) : PaymentPushGateway, PaymentStoreGateway {
    constructor(httpClient: ExternalHttpClient, properties: GooglePlayProperties, time: TimeProvider = TimeProvider.systemUtc()) :
        this(httpClient, properties, time, GooglePlayConfigCheck.credentialsOf(properties) ?: throw IllegalArgumentException("the Google Play service account is not usable: ${GooglePlayConfigCheck.problems(properties)}"))

    override val providerId: String = properties.providerId
    override val mode: PaymentMode = PaymentMode.LIVE
    override val signed: Boolean = true

    private val errorMapper = GooglePlayErrorMapper(providerId)
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val tokens = GoogleAccessTokens(credentials, httpClient, properties, time)
    private val oidc = GoogleOidcVerifier(httpClient, properties, time)

    /** 방금 확인한 구매의 토큰 - [afterGrant] 는 [StorePurchase] 만 받는데 승인 통지에는 토큰이 필요하다. 지급 직전 같은 요청 안에서 쓰이고, 오래된 것부터 버린다(상한 있음). */
    private val remembered = object : LinkedHashMap<String, Remembered>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Remembered>?): Boolean = size > REMEMBER_LIMIT
    }

    init {
        require(providerId.isNotBlank()) { "Google Play providerId must not be blank" }
    }

    private class Remembered(val token: String, val kind: StorePurchaseKind, val productId: String, val acknowledged: Boolean)

    private class Payment(val id: String, val amount: PaymentAmount, val at: Instant?, val refunded: Boolean)

    /** 구독 응답에서 읽은 지금 사실. [status] 는 구독이 장부에 줄 상태(보류 · 취소된 대기는 null). */
    private class Sub(
        val productId: String,
        val basePlanId: String?,
        val expiry: Instant?,
        val startTime: Instant?,
        val state: StorePurchaseState,
        val status: PushedSubscriptionStatus?,
        val mode: PaymentMode,
        val latestOrderId: String?,
        val linked: String?,
        val accountToken: String?,
        val acknowledged: Boolean,
    )

    // --- 구매 확인 ---

    override fun fetch(claim: StoreClaim): StorePurchase? {
        val token = claim.reference
        if (!SAFE_TOKEN.matches(token)) return null
        // 단건부터 묻고, 구글이 그 토큰을 단건으로 모르면(400 · 404) 구독으로 묻는다 - 앱이 보내는 힌트는 상품 id 뿐이다
        read(PRODUCT_V2, token)?.let { return oneTime(claim, token, it) }
        read(SUBSCRIPTION_V2, token)?.let { return subscription(claim, token, it) }
        return null
    }

    private fun oneTime(claim: StoreClaim, token: String, node: JsonNode): StorePurchase? {
        val item = node["productLineItem"]?.toList()?.singleOrNull() ?: throw GooglePlayFailures.unreadable(properties)
        val productId = item.text("productId") ?: throw GooglePlayFailures.unreadable(properties)
        if (claim.productId != null && claim.productId != productId) return null // 다른 상품의 토큰 - 앱이 댄 상품과 구글의 답이 다르면 믿지 않는다
        val declared = when (node["purchaseStateContext"].text("purchaseState")) {
            "PURCHASED" -> StorePurchaseState.PURCHASED
            "PENDING" -> StorePurchaseState.PENDING
            "CANCELLED" -> StorePurchaseState.REVOKED
            else -> throw GooglePlayFailures.unreadable(properties)
        }
        val purchaseMode = if (node.has("testPurchaseContext")) PaymentMode.TEST else PaymentMode.LIVE
        val orderId = node.text("orderId")
        val payment = if (declared == StorePurchaseState.PURCHASED) payment(token, orderId, purchaseMode) else unpaid(token, orderId)
        val acknowledged = node.text("acknowledgementState") == ACKNOWLEDGED
        remember(payment.id, Remembered(token, StorePurchaseKind.ONE_TIME, productId, acknowledged))
        return StorePurchase(
            mode = purchaseMode,
            kind = StorePurchaseKind.ONE_TIME,
            productId = productId,
            providerPaymentId = payment.id,
            accountToken = node.text("obfuscatedExternalAccountId"),
            amount = payment.amount,
            quantity = (item["productOfferDetails"]?.get("quantity")?.asInt(1) ?: 1).coerceAtLeast(1),
            paidAt = instantOf(node, "purchaseCompletionTime") ?: payment.at ?: time.now(),
            state = if (payment.refunded) StorePurchaseState.REVOKED else declared,
        )
    }

    private fun subscription(claim: StoreClaim, token: String, node: JsonNode): StorePurchase? {
        val sub = subOf(node)
        if (claim.productId != null && claim.productId != sub.productId) return null
        val payment = if (sub.state == StorePurchaseState.PURCHASED) payment(token, sub.latestOrderId, sub.mode) else unpaid(token, sub.latestOrderId)
        remember(payment.id, Remembered(token, StorePurchaseKind.SUBSCRIPTION, sub.productId, sub.acknowledged))
        val paidAt = payment.at ?: sub.startTime ?: time.now()
        return StorePurchase(
            mode = sub.mode,
            kind = StorePurchaseKind.SUBSCRIPTION,
            productId = sub.productId,
            basePlanId = sub.basePlanId,
            providerPaymentId = payment.id,
            providerSubscriptionId = sha256(token),
            replacesSubscriptionId = sub.linked?.let(::sha256),
            accountToken = sub.accountToken,
            amount = payment.amount,
            paidAt = paidAt,
            periodStart = paidAt,
            periodEnd = sub.expiry,
            state = if (payment.refunded) StorePurchaseState.REVOKED else sub.state,
            subscriptionStatus = sub.status,
        )
    }

    private fun subOf(node: JsonNode): Sub {
        val item = node["lineItems"]?.toList()?.singleOrNull() ?: throw GooglePlayFailures.unreadable(properties)
        val expiry = instantOf(item, "expiryTime")
        val (state, status) = when (node.text("subscriptionState")) {
            "SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> StorePurchaseState.PURCHASED to PushedSubscriptionStatus.ACTIVE
            "SUBSCRIPTION_STATE_CANCELED" -> StorePurchaseState.PURCHASED to if (expiry != null && expiry.isAfter(time.now())) PushedSubscriptionStatus.CANCELING else PushedSubscriptionStatus.ENDED
            "SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED" -> StorePurchaseState.PURCHASED to PushedSubscriptionStatus.PAST_DUE
            "SUBSCRIPTION_STATE_EXPIRED" -> StorePurchaseState.PURCHASED to PushedSubscriptionStatus.ENDED
            "SUBSCRIPTION_STATE_PENDING" -> StorePurchaseState.PENDING to null
            "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" -> StorePurchaseState.REVOKED to PushedSubscriptionStatus.ENDED
            else -> throw GooglePlayFailures.unreadable(properties)
        }
        return Sub(
            productId = item.text("productId") ?: throw GooglePlayFailures.unreadable(properties),
            basePlanId = item["offerDetails"].text("basePlanId"),
            expiry = expiry,
            startTime = instantOf(node, "startTime"),
            state = state,
            status = status,
            mode = if (node.has("testPurchase")) PaymentMode.TEST else PaymentMode.LIVE,
            latestOrderId = item.text("latestSuccessfulOrderId"),
            linked = node.text("linkedPurchaseToken"),
            accountToken = node["externalAccountIdentifiers"].text("obfuscatedExternalAccountId"),
            acknowledged = node.text("acknowledgementState") == ACKNOWLEDGED,
        )
    }

    /**
     * 이 주문의 금액 - 구글 `orders.get` 의 `total`. 못 읽으면 운영(`LIVE`)은 던지고(앱이 다시 보낸다 - 공짜 지급 없음), 시험 구매는 주문이 없을 수 있어(404) 0 원 · `XXX` · `pt:`+해시로 간다.
     * 주문이 환불된 상태(`REFUNDED`)면 [Payment.refunded] - 확인 전에 환불된 구매를 지급하지 않는다.
     */
    private fun payment(token: String, orderId: String?, purchaseMode: PaymentMode): Payment {
        val order = orderId?.takeIf { SAFE_ID.matches(it) }?.let { read(ORDER, it) }
        if (order == null) {
            if (purchaseMode == PaymentMode.LIVE) throw GooglePlayFailures.failure(properties, ORDER_UNAVAILABLE)
            return Payment("pt:" + sha256(token), PaymentAmount(0, NO_CURRENCY), null, false)
        }
        return Payment(orderId, money(order["total"] ?: throw GooglePlayFailures.unreadable(properties)), instantOf(order, "createTime"), order.text("state") == "REFUNDED")
    }

    /** 아직 돈이 오가지 않았거나(보류) 효력이 없는(취소) 구매 - 지급하지 않으니 금액을 묻지 않는다. */
    private fun unpaid(token: String, orderId: String?): Payment = Payment(orderId?.takeIf { SAFE_ID.matches(it) } ?: ("pt:" + sha256(token)), PaymentAmount(0, NO_CURRENCY), null, false)

    private fun money(node: JsonNode): PaymentAmount {
        val currency = node.text("currencyCode")?.uppercase() ?: throw GooglePlayFailures.unreadable(properties)
        val digits = try {
            Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)
        } catch (_: IllegalArgumentException) {
            throw GooglePlayFailures.unreadable(properties)
        }
        val units = node.text("units")?.toLongOrNull() ?: 0L
        val value = BigDecimal(units).add(BigDecimal(node["nanos"]?.asInt(0) ?: 0).movePointLeft(9))
        return PaymentAmount(value.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).toLong().coerceAtLeast(0), currency)
    }

    // --- 승인 통지 ---

    /** 지급이 커밋된 뒤 구글에 「받았다」고 알린다(3일 안에 하지 않으면 구글이 자동 환불). 소비(consume)는 앱이 한다. 이미 통지된 것은 정상이다. */
    override fun afterGrant(purchase: StorePurchase) {
        val pending = synchronized(remembered) { remembered.remove(purchase.providerPaymentId) } ?: run {
            log.warn("payment.googleplay.acknowledge skipped: the purchase token is no longer remembered kind={}", purchase.kind)
            return
        }
        if (pending.acknowledged) return
        val path = if (pending.kind == StorePurchaseKind.ONE_TIME) {
            if (!SAFE_ID.matches(pending.productId)) return
            ACK_PRODUCT.replace("{sku}", pending.productId)
        } else {
            ACK_SUBSCRIPTION
        }
        try {
            api { client, spec ->
                client.post(properties.clientName, path, emptyMap<String, String>(), String::class.java) {
                    spec(this)
                    uriVariable("token", pending.token)
                }.block()
            }
        } catch (e: PaymentProviderException) {
            if (e.providerError.upstreamStatus == 400) return // 이미 통지됐다
            throw e
        }
    }

    // --- 알림 (RTDN) ---

    override fun parse(delivery: WebhookDelivery): WebhookEnvelope {
        // 아래의 어느 것도 이 확인 전에는 읽지 않는다 - 저장은 봉투를 받은 처리 작업이 한다
        if (!oidc.verify(delivery.headers["authorization"])) throw PaymentWebhookRejectedException()
        val message = read(delivery.body.decodeToString())?.get("message")?.takeIf { it.isObject } ?: throw PaymentWebhookRejectedException()
        val messageId = message.text("messageId")?.takeIf { MESSAGE_ID.matches(it) } ?: throw PaymentWebhookRejectedException()
        val notification = try {
            read(String(Base64.getMimeDecoder().decode(message.text("data") ?: throw PaymentWebhookRejectedException()), Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw PaymentWebhookRejectedException()
        fun ignored(type: String) = WebhookEnvelope(eventKey = messageId, eventType = type, resourceType = "UNSUPPORTED", resourceId = messageId)

        if (notification.text("packageName") != properties.packageName) return ignored("OTHER_PACKAGE")
        val subscription = notification["subscriptionNotification"]
        val voided = notification["voidedPurchaseNotification"]
        val (type, token) = when {
            subscription != null -> (subscription["notificationType"]?.asInt() ?: return ignored("UNREADABLE")).let { SUBSCRIPTION_TYPES[it] } to subscription.text("purchaseToken")
            voided != null -> VOIDED to voided.text("purchaseToken")
            else -> return ignored("UNSUPPORTED")
        }
        if (type == null) return ignored("UNHANDLED_SUBSCRIPTION_EVENT")
        if (token == null || !SAFE_TOKEN.matches(token)) return ignored("UNREADABLE")
        val stored = mapper.createObjectNode().apply {
            put("messageId", messageId)
            set("notification", notification)
        }
        return WebhookEnvelope(eventKey = messageId, eventType = type, resourceType = PushedEvents.RESOURCE_TYPE, resourceId = sha256(token), storedPayload = mapper.writeValueAsString(stored))
    }

    /** 락 밖에서 부른다 - RTDN 은 신호일 뿐이라 토큰으로 API 를 다시 읽어 지금 사실로 사건을 만든다. 일시 실패는 던진다(수신함 줄이 다시 시도된다). */
    override fun interpret(payload: String): PushedPaymentEvent? {
        val root = read(payload) ?: error("unreadable Google Play payload")
        val eventId = root.text("messageId") ?: error("Google Play payload without a message id")
        val notification = root["notification"] ?: error("Google Play payload without a notification")
        val occurredAt = notification.text("eventTimeMillis")?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: error("Google Play payload without a date")
        notification["voidedPurchaseNotification"]?.let { return voided(eventId, occurredAt, it) }
        val sub = notification["subscriptionNotification"] ?: error("Google Play payload without a handled notification")
        val type = sub["notificationType"]?.asInt() ?: error("Google Play payload without a notification type")
        val token = sub.text("purchaseToken") ?: error("Google Play payload without a purchase token")
        val state = subOf(read(SUBSCRIPTION_V2, token) ?: return null)
        val items = listOf(PushedItem(state.productId, 1))
        if (SUBSCRIPTION_TYPES[type] in PAYMENT_EVENTS) {
            val payment = payment(token, state.latestOrderId, state.mode)
            return PushedPayment(
                eventId = eventId,
                occurredAt = occurredAt,
                providerPaymentId = payment.id,
                total = payment.amount,
                tax = null,
                paidAt = payment.at ?: occurredAt,
                providerSubscriptionId = sha256(token),
                periodStart = payment.at,
                periodEnd = state.expiry,
                items = items,
                mode = state.mode,
            )
        }
        val status = if (type == REVOKED_TYPE || type == EXPIRED_TYPE) PushedSubscriptionStatus.ENDED else state.status ?: return null
        return PushedSubscription(eventId = eventId, occurredAt = occurredAt, providerSubscriptionId = sha256(token), status = status, periodEnd = state.expiry, items = items, mode = state.mode)
    }

    /** `voidedPurchaseNotification` - 그 주문이 환불 · 취소됐다. 전액(`refundType=1`)만 읽는다: 수량 부분 환불(2)은 금액을 알 수 없어 사람이 본다(수신함 줄이 죽는다). */
    private fun voided(eventId: String, occurredAt: Instant, voided: JsonNode): PushedPaymentEvent {
        check(voided["refundType"]?.asInt() == FULL_REFUND) { "a partial refund by quantity needs a person to look at it" }
        val token = voided.text("purchaseToken") ?: error("Google Play payload without a purchase token")
        val subscription = voided["productType"]?.asInt() == SUBSCRIPTION_PRODUCT_TYPE
        val purchase = read(if (subscription) SUBSCRIPTION_V2 else PRODUCT_V2, token)
        val purchaseMode = purchase?.let { if (it.has("testPurchase") || it.has("testPurchaseContext")) PaymentMode.TEST else PaymentMode.LIVE } ?: PaymentMode.LIVE
        val orderId = voided.text("orderId")?.takeIf { SAFE_ID.matches(it) }
        val order = orderId?.let { read(ORDER, it) }
        val amount = when {
            order != null -> money(order["total"] ?: throw GooglePlayFailures.unreadable(properties))
            purchaseMode == PaymentMode.TEST -> PaymentAmount(0, NO_CURRENCY)
            else -> throw GooglePlayFailures.failure(properties, ORDER_UNAVAILABLE)
        }
        val paymentId = orderId ?: ("pt:" + sha256(token))
        return PushedRefund(
            eventId = eventId,
            occurredAt = occurredAt,
            kind = PushedRefundKind.REFUND,
            providerRefundId = "void-$paymentId",
            providerPaymentId = paymentId,
            status = PushedRefundStatus.APPROVED,
            amount = amount,
            providerSubscriptionId = sha256(token).takeIf { subscription },
            mode = purchaseMode,
        )
    }

    override fun priceIdOf(itemRef: String): String? = null

    // --- 구글 API ---

    /** 이 주소의 JSON - 구글이 모르면(400 · 404 · 410) null. 그 밖의 오류(429 · 5xx · 시간 초과)는 던진다. */
    private fun read(path: String, id: String): JsonNode? =
        try {
            api { client, spec ->
                client.get(properties.clientName, path, String::class.java) {
                    spec(this)
                    uriVariable(if (path == ORDER) "order" else "token", id)
                }.block()
            }?.let { read(it) }
        } catch (e: PaymentProviderException) {
            if (e.providerError.upstreamStatus in NOT_FOUND_STATUSES) null else throw e
        }

    private fun <T> api(call: (ExternalHttpClient, spec: dev.sumin.skeleton.common.http.ExternalHttpRequestSpec.() -> Unit) -> T): T {
        val spec: dev.sumin.skeleton.common.http.ExternalHttpRequestSpec.() -> Unit = {
            baseUrl(properties.resolvedBaseUrl)
            uriVariable("pkg", properties.packageName)
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.get()}")
            timeout(properties.lookupTimeout)
            loggingTag("payment.googleplay.api")
            errorMapper(errorMapper)
        }
        try {
            return call(httpClient, spec)
        } catch (e: PaymentProviderException) {
            if (e.providerError.upstreamStatus == 401) tokens.invalidate() // 다음 호출이 새 토큰으로
            throw e
        }
    }

    private fun remember(paymentId: String, entry: Remembered) {
        synchronized(remembered) { remembered[paymentId] = entry }
    }

    // --- 값 읽기 ---

    private fun instantOf(node: JsonNode, name: String): Instant? = node.text(name)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun read(text: String): JsonNode? = try {
        mapper.readTree(text)?.takeIf { it.isObject }
    } catch (_: Exception) {
        null
    }

    private fun JsonNode?.text(name: String): String? = this?.get(name)?.takeIf { it.isString || it.isNumber }?.asString()?.takeIf { it.isNotBlank() }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val log = LoggerFactory.getLogger(GooglePlayPaymentProvider::class.java)

        const val BASE = "/androidpublisher/v3/applications/{pkg}"
        const val PRODUCT_V2 = "$BASE/purchases/productsv2/tokens/{token}"
        const val SUBSCRIPTION_V2 = "$BASE/purchases/subscriptionsv2/tokens/{token}"
        const val ORDER = "$BASE/orders/{order}"
        const val ACK_PRODUCT = "$BASE/purchases/products/{sku}/tokens/{token}:acknowledge"
        const val ACK_SUBSCRIPTION = "$BASE/purchases/subscriptions/tokens/{token}:acknowledge"

        const val ACKNOWLEDGED = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
        const val NO_CURRENCY = "XXX"
        const val ORDER_UNAVAILABLE = "ORDER_UNAVAILABLE"
        const val VOIDED = "VOIDED_PURCHASE"
        const val FULL_REFUND = 1
        const val SUBSCRIPTION_PRODUCT_TYPE = 1
        const val REVOKED_TYPE = 12
        const val EXPIRED_TYPE = 13
        const val REMEMBER_LIMIT = 2000
        val NOT_FOUND_STATUSES = setOf(400, 404, 410)

        /** 장부가 따라가는 구독 알림(§2.3 표). 첫 구매(4) · 가격 변경(8 · 19 · 22) · 미룸(9) · 일시정지 예약(11) · 대기 취소(20) 같은 나머지는 `IGNORED` - 첫 구매는 앱이 확인받는다. */
        val SUBSCRIPTION_TYPES = mapOf(
            1 to "SUBSCRIPTION_RECOVERED", 2 to "SUBSCRIPTION_RENEWED", 3 to "SUBSCRIPTION_CANCELED", 5 to "SUBSCRIPTION_ON_HOLD", 6 to "SUBSCRIPTION_IN_GRACE_PERIOD",
            7 to "SUBSCRIPTION_RESTARTED", 10 to "SUBSCRIPTION_PAUSED", REVOKED_TYPE to "SUBSCRIPTION_REVOKED", EXPIRED_TYPE to "SUBSCRIPTION_EXPIRED",
        )
        val PAYMENT_EVENTS = setOf("SUBSCRIPTION_RENEWED", "SUBSCRIPTION_RECOVERED")

        val SAFE_TOKEN = Regex("[A-Za-z0-9._-]{1,2048}")
        val SAFE_ID = Regex("[A-Za-z0-9._-]{1,200}")
        val MESSAGE_ID = Regex("[A-Za-z0-9._:-]{1,200}")
    }
}
