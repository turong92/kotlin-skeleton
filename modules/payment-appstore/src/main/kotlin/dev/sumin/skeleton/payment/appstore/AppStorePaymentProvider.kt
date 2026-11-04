package dev.sumin.skeleton.payment.appstore

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.PaymentStoreGateway
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.ProviderTrace
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
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Currency
import java.util.Date
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * App Store(앱 안 결제) — 구매는 이미 스토어 안에서 끝났다. 서버는 앱이 보낸 영수증을 믿지 않고 **거래 id 로 App Store Server API 에 직접 묻고**([fetch]),
 * 애플이 보내는 **서명된 알림**(Notifications V2)으로 갱신 · 만료 · 환불을 받는다([parse] · [interpret]). 애플이 서명한 모든 JWS 는 [AppStoreSignatureVerifier] 가 고정 루트까지 확인한 뒤에만 읽는다.
 *
 * **모드는 거래마다 다르다**: 이 결제사의 [mode] 는 `LIVE` 이고, 샌드박스(심사 · TestFlight)·그 밖 비운영 거래는 거래 · 사건의 모드가 `TEST` 라 대시보드 매출에서 빠진다.
 * 금액은 애플 거래의 `price`(밀리 단위)를 통화 최소 단위로 옮긴 참고값이다 — 애플은 이 값을 회계에 쓰지 말라고 한다.
 */
class AppStorePaymentProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: AppStoreProperties,
    private val verifier: AppStoreSignatureVerifier,
    private val time: TimeProvider = TimeProvider.systemUtc(),
) : PaymentPushGateway, PaymentStoreGateway {
    override val providerId: String = properties.providerId
    override val mode: PaymentMode = PaymentMode.LIVE
    override val signed: Boolean = true

    private val errorMapper = AppStoreErrorMapper(providerId)
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val signingKey: ECPrivateKey by lazy { parsePrivateKey(properties.privateKey) }

    init {
        require(providerId.isNotBlank()) { "App Store providerId must not be blank" }
    }

    // --- 구매 확인 ---

    override fun fetch(claim: StoreClaim): StorePurchase? {
        val id = claim.reference
        if (!SAFE_ID.matches(id)) return null
        // 운영 주소에 먼저, 모르면(404) 샌드박스에 - 심사 · TestFlight 구매는 샌드박스 거래다
        val (host, answer) = askTransaction(properties.resolvedBaseUrl, id)?.let { properties.resolvedBaseUrl to it }
            ?: askTransaction(properties.resolvedSandboxBaseUrl, id)?.let { properties.resolvedSandboxBaseUrl to it }
            ?: return null
        val claimed = transactionOf(answer.text("signedTransactionInfo"))
        if (claimed.text("transactionId") != id) throw unreadable()
        if (claimed.text("type") != AUTO_RENEWABLE) return oneTime(claimed)
        return subscription(host, claimed)
    }

    private fun oneTime(tx: JsonNode): StorePurchase =
        StorePurchase(
            mode = modeOf(tx),
            kind = StorePurchaseKind.ONE_TIME,
            productId = tx.text("productId") ?: throw unreadable(),
            providerPaymentId = tx.text("transactionId") ?: throw unreadable(),
            accountToken = tx.text("appAccountToken"),
            amount = amountOf(tx),
            quantity = tx["quantity"]?.asInt(1) ?: 1,
            paidAt = instantOf(tx, "purchaseDate") ?: throw unreadable(),
            state = if (tx.has("revocationDate")) StorePurchaseState.REVOKED else StorePurchaseState.PURCHASED,
        )

    private fun subscription(host: String, claimed: JsonNode): StorePurchase {
        val original = claimed.text("originalTransactionId") ?: throw unreadable()
        val answer = read(get(host, "/inApps/v1/subscriptions/{id}", claimed.text("transactionId")!!) ?: throw unreadable()) ?: throw unreadable()
        checkApp(answer.text("bundleId"), answer.text("environment"), answer.text("appAppleId"))
        val last = answer["data"]?.toList().orEmpty().flatMap { it["lastTransactions"]?.toList().orEmpty() }.firstOrNull { it.text("originalTransactionId") == original } ?: throw unreadable()
        val latest = transactionOf(last.text("signedTransactionInfo"))
        val renewal = last.text("signedRenewalInfo")?.let { read(verify(it)) }
        val status = last["status"]?.asInt() ?: throw unreadable()
        val renewing = renewal?.get("autoRenewStatus")?.asInt(1) != 0
        val graceEnd = renewal?.let { instantOf(it, "gracePeriodExpiresDate") }
        val periodEnd = instantOf(latest, "expiresDate") ?: throw unreadable()
        return StorePurchase(
            mode = modeOf(latest),
            kind = StorePurchaseKind.SUBSCRIPTION,
            productId = latest.text("productId") ?: throw unreadable(),
            providerPaymentId = latest.text("transactionId") ?: throw unreadable(),
            providerSubscriptionId = original,
            accountToken = latest.text("appAccountToken") ?: claimed.text("appAccountToken"),
            amount = amountOf(latest),
            quantity = latest["quantity"]?.asInt(1) ?: 1,
            paidAt = instantOf(latest, "purchaseDate") ?: throw unreadable(),
            periodStart = instantOf(latest, "purchaseDate"),
            periodEnd = if (status == STATUS_GRACE) graceEnd ?: periodEnd else periodEnd,
            state = if (status == STATUS_REVOKED || latest.has("revocationDate")) StorePurchaseState.REVOKED else StorePurchaseState.PURCHASED,
            subscriptionStatus = when (status) {
                STATUS_ACTIVE -> if (renewing) PushedSubscriptionStatus.ACTIVE else PushedSubscriptionStatus.CANCELING
                STATUS_RETRY -> PushedSubscriptionStatus.PAST_DUE
                STATUS_GRACE -> PushedSubscriptionStatus.ACTIVE
                else -> PushedSubscriptionStatus.ENDED // 만료 · 취소
            },
        )
    }

    /** 이 호스트에 거래를 묻는다 - 호스트가 모르면(404) null. 그 밖의 오류는 던진다. */
    private fun askTransaction(host: String, id: String): JsonNode? =
        try {
            read(get(host, "/inApps/v1/transactions/{id}", id) ?: throw unreadable()) ?: throw unreadable()
        } catch (e: PaymentProviderException) {
            if (e.providerError.upstreamStatus == 404) null else throw e
        }

    private fun get(host: String, path: String, id: String): String? =
        httpClient.get(
            clientName = properties.clientName,
            path = path,
            responseType = String::class.java,
        ) {
            baseUrl(host)
            uriVariable("id", id)
            header(HttpHeaders.AUTHORIZATION, "Bearer ${apiToken()}")
            timeout(properties.lookupTimeout)
            loggingTag("payment.appstore.lookup")
            errorMapper(errorMapper)
        }.block()

    /** 서버 API 인증 JWT - ES256, `kid` · `typ: JWT`, 5분(애플은 60분 넘는 것을 거절한다). */
    private fun apiToken(): String {
        val now = time.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(properties.issuerId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
            .audience(AUDIENCE)
            .claim("bid", properties.bundleId)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(properties.keyId).type(JOSEObjectType.JWT).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(signingKey)) }.serialize()
    }

    /** 서명된 거래 정보를 확인하고 읽는다 - 우리 앱의 거래여야 한다. */
    private fun transactionOf(jws: String?): JsonNode {
        val tx = read(verify(jws ?: throw unreadable())) ?: throw unreadable()
        if (tx.text("bundleId") != properties.bundleId) throw invalid()
        return tx
    }

    private fun verify(jws: String): String =
        try {
            verifier.verify(jws)
        } catch (_: AppStoreSignatureException) {
            throw invalid()
        }

    // --- 알림 ---

    override fun parse(delivery: WebhookDelivery): WebhookEnvelope {
        val signedPayload = read(delivery.body.decodeToString())?.text("signedPayload") ?: throw PaymentWebhookRejectedException()
        // 이 아래는 서명을 확인한 뒤의 일이다 - 확인 전에는 아무것도 저장하지 않는다(저장은 처리 작업이 봉투를 받아서 한다)
        val root = try {
            read(verifier.verify(signedPayload))
        } catch (_: AppStoreSignatureException) {
            null
        } ?: throw PaymentWebhookRejectedException()
        val type = root.text("notificationType") ?: throw PaymentWebhookRejectedException()
        val uuid = root.text("notificationUUID") ?: throw PaymentWebhookRejectedException()
        val data = root["data"]
        if (data != null || type != TEST) {
            if (data == null || !appMatches(data.text("bundleId"), data.text("environment"), data.text("appAppleId"))) throw PaymentWebhookRejectedException()
        }
        val subtype = root.text("subtype")
        val signedTransaction = data?.text("signedTransactionInfo")
        if (type == TEST || !handled(type, subtype) || signedTransaction == null) {
            return WebhookEnvelope(eventKey = uuid, eventType = type, resourceType = "UNSUPPORTED", resourceId = uuid)
        }
        val tx = try {
            read(verifier.verify(signedTransaction))?.takeIf { it.text("bundleId") == properties.bundleId && it.text("transactionId") != null }
        } catch (_: AppStoreSignatureException) {
            null
        } ?: throw PaymentWebhookRejectedException()
        val renewal = data.text("signedRenewalInfo")?.let {
            try {
                read(verifier.verify(it))
            } catch (_: AppStoreSignatureException) {
                throw PaymentWebhookRejectedException()
            }
        }
        val stored = mapper.createObjectNode().apply {
            put("notificationUUID", uuid)
            put("notificationType", type)
            subtype?.let { put("subtype", it) }
            put("signedDate", root["signedDate"]?.asLong() ?: 0L)
            set("transaction", tx)
            renewal?.let { set("renewal", it) }
        }
        return WebhookEnvelope(eventKey = uuid, eventType = type, resourceType = PushedEvents.RESOURCE_TYPE, resourceId = tx.text("transactionId")!!, storedPayload = mapper.writeValueAsString(stored))
    }

    /** 장부가 다루는 알림인가(§2.3 표). `TEST` · 소비 요청 · 가격 인상 동의 · 내리기 예약 같은 나머지는 `IGNORED`. */
    private fun handled(type: String, subtype: String?): Boolean =
        when (type) {
            "REFUND", "REFUND_REVERSED", "DID_RENEW", "DID_FAIL_TO_RENEW", "GRACE_PERIOD_EXPIRED", "EXPIRED", "REVOKE" -> true
            "DID_CHANGE_RENEWAL_STATUS" -> subtype == "AUTO_RENEW_DISABLED" || subtype == "AUTO_RENEW_ENABLED"
            "DID_CHANGE_RENEWAL_PREF" -> subtype == "UPGRADE"
            else -> false
        }

    override fun interpret(payload: String): PushedPaymentEvent? {
        val root = read(payload) ?: error("unreadable App Store payload")
        val tx = root["transaction"] ?: error("App Store payload without a transaction")
        val renewal = root["renewal"]
        val eventId = root.text("notificationUUID") ?: error("App Store payload without a notification uuid")
        val occurredAt = Instant.ofEpochMilli(root["signedDate"]?.asLong() ?: error("App Store payload without a date"))
        val type = root.text("notificationType")
        val subtype = root.text("subtype")
        val subscription = tx.text("type") == AUTO_RENEWABLE
        val eventMode = modeOf(tx)
        val transactionId = tx.text("transactionId") ?: error("App Store payload without a transaction id")
        val originalId = tx.text("originalTransactionId") ?: transactionId
        val items = tx.text("productId")?.let { listOf(PushedItem(it, 1)) }.orEmpty()
        return when (type) {
            "REFUND", "REFUND_REVERSED" -> PushedRefund(
                eventId = eventId,
                occurredAt = occurredAt,
                kind = PushedRefundKind.REFUND,
                providerRefundId = "refund-$transactionId",
                providerPaymentId = transactionId,
                status = if (type == "REFUND") PushedRefundStatus.APPROVED else PushedRefundStatus.REVERSED,
                amount = refundAmount(tx),
                providerSubscriptionId = originalId.takeIf { subscription },
                mode = eventMode,
            )
            "DID_RENEW" -> if (!subscription) null else PushedPayment(
                eventId = eventId,
                occurredAt = occurredAt,
                providerPaymentId = transactionId,
                total = amountOf(tx),
                tax = null,
                paidAt = instantOf(tx, "purchaseDate") ?: occurredAt,
                providerSubscriptionId = originalId,
                periodStart = instantOf(tx, "purchaseDate"),
                periodEnd = instantOf(tx, "expiresDate"),
                items = items,
                mode = eventMode,
            )
            else -> if (!subscription) null else {
                val status = when (type) {
                    "DID_FAIL_TO_RENEW" -> if (subtype == "GRACE_PERIOD") PushedSubscriptionStatus.ACTIVE else PushedSubscriptionStatus.PAST_DUE
                    "GRACE_PERIOD_EXPIRED" -> PushedSubscriptionStatus.PAST_DUE
                    "DID_CHANGE_RENEWAL_STATUS" -> if (subtype == "AUTO_RENEW_DISABLED") PushedSubscriptionStatus.CANCELING else PushedSubscriptionStatus.ACTIVE
                    "DID_CHANGE_RENEWAL_PREF" -> PushedSubscriptionStatus.ACTIVE
                    "EXPIRED", "REVOKE" -> PushedSubscriptionStatus.ENDED
                    else -> return null
                }
                val graceEnd = if (type == "DID_FAIL_TO_RENEW" && subtype == "GRACE_PERIOD") renewal?.let { instantOf(it, "gracePeriodExpiresDate") } else null
                PushedSubscription(
                    eventId = eventId,
                    occurredAt = occurredAt,
                    providerSubscriptionId = originalId,
                    status = status,
                    periodStart = instantOf(tx, "purchaseDate"),
                    periodEnd = graceEnd ?: instantOf(tx, "expiresDate"),
                    items = items,
                    mode = eventMode,
                )
            }
        }
    }

    /** 이 알림이 우리 앱의 것인가 - 번들 id 가 같고, 운영이면 앱 id 도 같다(샌드박스 알림에는 앱 id 가 없다). */
    private fun appMatches(bundleId: String?, environment: String?, appAppleId: String?): Boolean =
        bundleId == properties.bundleId && (environment != PRODUCTION || appAppleId == properties.appAppleId)

    private fun checkApp(bundleId: String?, environment: String?, appAppleId: String?) {
        if (!appMatches(bundleId, environment, appAppleId)) throw invalid()
    }

    // --- 값 읽기 ---

    private fun modeOf(tx: JsonNode): PaymentMode = if (tx.text("environment") == PRODUCTION) PaymentMode.LIVE else PaymentMode.TEST

    private fun instantOf(node: JsonNode, name: String): Instant? = node[name]?.takeIf { it.isNumber }?.let { Instant.ofEpochMilli(it.asLong()) }

    /** 환불 금액 - `REFUND_PRORATED` 는 `revocationPercentage`(밀리 단위: 100000 = 100%)만큼, 그 밖은 거래 전액. */
    private fun refundAmount(tx: JsonNode): PaymentAmount {
        val full = amountOf(tx)
        val share = tx["revocationPercentage"]?.takeIf { it.isNumber && tx.text("revocationType") == "REFUND_PRORATED" }?.asLong() ?: return full
        val part = BigDecimal(full.amount).multiply(BigDecimal(share.coerceIn(0L, FULL_SHARE))).divide(BigDecimal(FULL_SHARE), 0, RoundingMode.HALF_UP)
        return PaymentAmount(part.toLong(), full.currency)
    }

    /** 애플의 `price`(통화 단위 × 1000)를 그 통화의 최소 단위로. 가격이 없으면(무료 체험) 0 - 통화가 없으면 ISO 4217 의 「통화 없음」 `XXX`. */
    private fun amountOf(tx: JsonNode): PaymentAmount {
        val currency = tx.text("currency")?.uppercase() ?: return PaymentAmount(0, NO_CURRENCY)
        val price = tx["price"]?.takeIf { it.isNumber }?.asLong() ?: return PaymentAmount(0, currency)
        val digits = try {
            Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)
        } catch (_: IllegalArgumentException) {
            throw unreadable()
        }
        return PaymentAmount(BigDecimal(price).movePointRight(digits).divide(BigDecimal(1000), 0, RoundingMode.HALF_UP).toLong(), currency)
    }

    override fun priceIdOf(itemRef: String): String? = null

    private fun read(text: String): JsonNode? = try {
        mapper.readTree(text)?.takeIf { it.isObject }
    } catch (_: Exception) {
        null
    }

    private fun JsonNode?.text(name: String): String? = this?.get(name)?.takeIf { it.isString || it.isNumber }?.asString()?.takeIf { it.isNotBlank() }

    /** 서명이 틀렸거나 다른 앱의 답 - 믿지 않는다(다시 해도 같다). */
    private fun invalid(): PaymentProviderException = failure(INVALID_ANSWER, retryable = false)

    /** 읽을 수 없는 답. */
    private fun unreadable(): PaymentProviderException = failure(UNREADABLE_RESPONSE, retryable = true)

    private fun failure(code: String, retryable: Boolean): PaymentProviderException =
        PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId, code = code, message = null, trace = ProviderTrace(provider = providerId, rawCode = code), upstreamStatus = 200, retryable = retryable,
            ),
            clientName = properties.clientName,
            method = "GET",
            uri = URI.create(properties.resolvedBaseUrl),
        )

    private companion object {
        const val PRODUCTION = "Production"
        const val AUTO_RENEWABLE = "Auto-Renewable Subscription"
        const val TEST = "TEST"
        const val AUDIENCE = "appstoreconnect-v1"
        const val INVALID_ANSWER = "INVALID_ANSWER"
        const val UNREADABLE_RESPONSE = "UNREADABLE_RESPONSE"
        const val NO_CURRENCY = "XXX"
        const val FULL_SHARE = 100_000L
        const val STATUS_ACTIVE = 1
        const val STATUS_RETRY = 3
        const val STATUS_GRACE = 4
        const val STATUS_REVOKED = 5
        val TOKEN_LIFETIME: Duration = Duration.ofMinutes(5)
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun parsePrivateKey(pem: String): ECPrivateKey {
            val body = pem.replace("\\n", "\n").lineSequence().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))) as ECPrivateKey
        }
    }
}
