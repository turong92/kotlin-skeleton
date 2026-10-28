package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.PaymentBuyerCountryGateway
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentRefundGateway
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentSnapshot
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.ProviderTrace
import dev.sumin.skeleton.payment.PushedRefund
import dev.sumin.skeleton.payment.PushedRefundKind
import dev.sumin.skeleton.payment.PushedRefundStatus
import dev.sumin.skeleton.payment.RefundDeclineCategory
import dev.sumin.skeleton.payment.PaymentHostedSubscriptionGateway
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentPricePreviewGateway
import dev.sumin.skeleton.payment.PricePreviewSetup
import dev.sumin.skeleton.payment.PaymentPushGateway
import dev.sumin.skeleton.payment.PaymentTransactionGateway
import dev.sumin.skeleton.payment.PaymentUnavailableException
import dev.sumin.skeleton.payment.ProviderPriceRefs
import dev.sumin.skeleton.payment.TransactionRequest
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.PushedEvents
import dev.sumin.skeleton.payment.PushedItem
import dev.sumin.skeleton.payment.PushedPayment
import dev.sumin.skeleton.payment.PushedPaymentFailure
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
 * Paddle(판매 대행) — 결제창은 프론트의 Paddle.js, 결제 · 갱신은 Paddle 이 하고 우리는 **서명된 웹훅**으로 결과를 받는다. 서버가 Paddle API 를 부르는 것은 거래 만들기 · 구독 해지 · 환불 · 거래 조회다.
 * 결제를 서버가 승인하는 능력은 없다. 웹훅 본문의 금액 · 통화가 그대로 장부에 간다.
 *
 * **환불**은 거래에 adjustment(`action=refund`)를 만든다. 실계정은 대부분 Paddle 이 검토한 뒤(`pending_approval` → `approved` · `rejected`) 결과를 웹훅으로 알린다(샌드박스는 10분마다 자동 승인).
 * Paddle 에는 멱등 키 헤더가 없다 - 「환불 한 줄에 호출 한 번」을 장부가 지킨다. **조회**는 거래 번호로만 된다([answersByOrderId] = false).
 */
class PaddlePaymentProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: PaddleProperties,
    private val time: TimeProvider = TimeProvider.systemUtc(),
    /** 결제사 가격 id 의 출처(카탈로그의 링크) - 여기서 먼저 찾고 없으면 [PaddleProperties.prices] 지도. 기본은 링크 없음. */
    private val priceRefs: ProviderPriceRefs = ProviderPriceRefs.NONE,
) : PaymentPushGateway, PaymentCheckoutGateway, PaymentHostedSubscriptionGateway, PaymentTransactionGateway, PaymentPricePreviewGateway, PaymentBuyerCountryGateway, PaymentRefundGateway, PaymentLookupGateway {
    override val providerId: String = properties.providerId
    override val supportedCurrencies: Set<String> = properties.supportedCurrencies
    override val supportedCountries: Set<String> = properties.supportedCountries
    override val mode: PaymentMode = if (properties.sandbox) PaymentMode.TEST else PaymentMode.LIVE
    override val signed: Boolean = true
    override val answersByOrderId: Boolean = false

    private val lookupMapper = PaddleErrorMapper(providerId, definitiveAllowed = false)
    private val refundMapper = PaddleErrorMapper(providerId, definitiveAllowed = true)

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
            "transaction.payment_failed" -> paymentFailure(eventId, occurredAt, data, custom)
            in ADJUSTMENT_EVENTS -> adjustment(eventId, occurredAt, data)
            in SUBSCRIPTION_EVENTS -> subscription(eventId, occurredAt, data, custom, type)
            else -> null
        }
    }

    /**
     * 환불 결정 - `refund`(상태 `pending_approval` 접수 · `approved` · `rejected`)와 차지백을 읽는다(`credit` · `credit_reverse` 는 장부가 다루지 않는다 → null).
     * 차지백: `chargeback` · `chargeback_warning` 의 `approved`(Paddle 이 돈을 가져갔다)와 `reversed`(분쟁에서 이겨 되돌렸다 - 원래 adjustment 의 번호가 온다),
     * 그리고 `chargeback_reverse` · `chargeback_warning_reverse` 의 `approved`(되돌림 adjustment 가 만들어졌다). 금액 · 통화는 Paddle 이 말한 그대로(세금 포함 총액)다.
     */
    private fun adjustment(eventId: String, occurredAt: Instant, data: JsonNode): PushedRefund? {
        val action = data.text("action")
        val status = when (data.text("status")) {
            "pending_approval" -> PushedRefundStatus.PENDING_APPROVAL
            "approved" -> PushedRefundStatus.APPROVED
            "rejected" -> PushedRefundStatus.REJECTED
            "reversed" -> PushedRefundStatus.REVERSED
            else -> return null
        }
        val kind = when (action) {
            REFUND -> PushedRefundKind.REFUND.takeIf { status != PushedRefundStatus.REVERSED }
            "chargeback", "chargeback_warning" -> PushedRefundKind.CHARGEBACK.takeIf { status == PushedRefundStatus.APPROVED || status == PushedRefundStatus.REVERSED }
            "chargeback_reverse", "chargeback_warning_reverse" -> PushedRefundKind.CHARGEBACK_REVERSAL.takeIf { status == PushedRefundStatus.APPROVED }
            else -> null
        } ?: return null
        return PushedRefund(
            eventId = eventId,
            occurredAt = occurredAt,
            kind = kind,
            warning = action == "chargeback_warning" || action == "chargeback_warning_reverse",
            providerRefundId = data.text("id") ?: error("Paddle adjustment without id"),
            providerPaymentId = data.text("transaction_id") ?: error("Paddle adjustment without transaction_id"),
            status = status,
            providerSubscriptionId = data.text("subscription_id"),
            amount = PaymentAmount(
                data["totals"]?.text("total")?.toLongOrNull() ?: error("Paddle adjustment without a total"),
                data.text("currency_code") ?: data["totals"]?.text("currency_code") ?: error("Paddle adjustment without a currency"),
            ),
        )
    }

    /** 사유 코드만(`payments[].error_code`, 가장 마지막 실패 시도) - 설명 문장 · 카드 정보는 읽지 않는다. */
    private fun paymentFailure(eventId: String, occurredAt: Instant, data: JsonNode, custom: JsonNode?): PushedPaymentFailure =
        PushedPaymentFailure(
            eventId = eventId,
            occurredAt = occurredAt,
            providerPaymentId = data.text("id") ?: error("Paddle transaction without id"),
            errorCode = data["payments"]?.toList().orEmpty().lastOrNull { it.text("error_code") != null }?.text("error_code"),
            ourOrderId = custom.text(PushedEvents.ORDER_KEY),
        )

    /** Paddle 가격의 결제 주기 `{interval, frequency}` → ISO-8601(`P1M`). 1회 가격(`null`)이나 모르는 모양은 null. */
    private fun cycleOf(cycle: JsonNode?): String? {
        val unit = when (cycle?.text("interval")) { "day" -> "D"; "week" -> "W"; "month" -> "M"; "year" -> "Y"; else -> return null }
        val frequency = cycle?.get("frequency")?.asInt(0)?.takeIf { it > 0 } ?: return null
        return "P$frequency$unit"
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
            period = cycleOf(data["items"]?.toList().orEmpty().firstNotNullOfOrNull { it["price"]?.get("billing_cycle")?.takeIf { cycle -> cycle.isObject } }),
            customerId = data.text("customer_id"),
            addressId = data.text("address_id"),
        )
    }

    /** 결제 본문에는 주소의 나라가 없다(`address_id` 뿐) - 주소를 읽어 `country_code` 를 얻는다(`GET /customers/{id}/addresses/{id}`). */
    override fun buyerCountry(customerId: String, addressId: String): String? {
        val response = requireNotNull(
            httpClient.get(
                clientName = properties.clientName,
                path = "/customers/{customer}/addresses/{address}",
                responseType = String::class.java,
            ) {
                baseUrl(properties.resolvedBaseUrl)
                uriVariable("customer", customerId)
                uriVariable("address", addressId)
                header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
                timeout(properties.createTransactionTimeout)
                loggingTag("payment.paddle.buyer-country")
            }.block(),
        )
        return read(response)?.get("data")?.text("country_code")?.uppercase()?.takeIf { it.length == 2 }
    }

    override fun pricePreview(): PricePreviewSetup? {
        if (properties.clientToken.isBlank()) return null
        val prices = properties.prices.filterValues { it.isNotBlank() } + priceRefs.all(providerId.normalizedId())
        return PricePreviewSetup(properties.clientToken, properties.environment.trim().lowercase(), prices)
    }

    /** 가격 id 를 찾는 순서: 카탈로그의 링크 → 설정 지도(`prices`). 링크를 바꾸면 그때 열려 있던 결제창의 품목 대조가 「확인 필요」로 간다(설정을 바꿔도 같다). */
    override fun priceIdOf(itemRef: String): String? =
        priceRefs.priceIdOf(providerId.normalizedId(), itemRef)?.takeIf { it.isNotBlank() } ?: properties.prices[itemRef]?.takeIf { it.isNotBlank() }

    private fun String.normalizedId() = trim().lowercase()

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
            period = cycleOf(data["billing_cycle"] ?: data["items"]?.toList().orEmpty().firstNotNullOfOrNull { it["price"]?.get("billing_cycle")?.takeIf { cycle -> cycle.isObject } }),
        )
    }

    // --- 결제창 ---

    override fun canOpenCheckout(): Boolean = properties.clientToken.isNotBlank()

    /** 거래를 서버가 먼저 만든다 - 품목(가격 · 수량)과 `custom_data`(우리 줄 id)는 서버가 정한 값이다. 가격이 설정에 없으면 던진다. */
    override fun createTransaction(request: TransactionRequest): String {
        // 가격 id 가 없으면 팔 수 없다 - 이 결제사로는 503 이고 거래는 만들지 않는다
        val price = priceIdOf(request.itemRef) ?: throw PaymentUnavailableException()
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

    // --- 거래 조회 · 환불 ---

    /** 거래 번호로만 묻는다 - 주문 번호만 든 조회는 부르지 않고 「결제 없음」이다. Paddle 이 모르는 거래(404)도 null. 그 밖의 실패는 던진다(모름 - 돈에 대해 아무것도 말하지 않는다). */
    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? {
        val id = query.providerPaymentId?.takeIf { SAFE_ID.matches(it) } ?: return null
        val transaction = try {
            transaction(id)
        } catch (e: PaymentProviderException) {
            if (e.providerError.upstreamStatus == 404) return null
            throw e
        }
        // 같은 이의의 경고와 차지백(같은 금액)은 한 번만 센다 - 장부가 같은 규칙으로 한 줄로 넣는다
        val approved = transaction.adjustments.filter { it.approved && it.action in TAKEN_ACTIONS }
        val warnings = approved.filter { it.action == CHARGEBACK_WARNING }
        // 남은 금액만 접어 센다 - 취소 목록에는 모두 싣고 같은 이의인지는 장부가 한 규칙으로 가린다(웹훅 기록과 같은 규칙)
        val takenTotal = approved.filterNot { it.action == CHARGEBACK && warnings.any { warning -> warning.total == it.total } }.sumOf { it.total }
        val taken = approved
        return PaymentSnapshot(
            providerPaymentId = id,
            orderId = null,
            status = when (transaction.status) {
                "completed" -> if (takenTotal == 0L) ProviderPaymentStatus.PAID else if (takenTotal >= transaction.total) ProviderPaymentStatus.REFUNDED else ProviderPaymentStatus.PARTIALLY_REFUNDED
                "canceled" -> ProviderPaymentStatus.FAILED
                else -> ProviderPaymentStatus.IN_PROGRESS
            },
            total = PaymentAmount(transaction.total, transaction.currency),
            remaining = PaymentAmount((transaction.total - takenTotal).coerceAtLeast(0), transaction.currency),
            method = null,
            paidAt = transaction.billedAt,
            refunds = taken.map {
                ProviderRefund(
                    it.id, PaymentAmount(it.total, transaction.currency), it.createdAt ?: transaction.billedAt ?: time.now(),
                    chargeback = it.action != REFUND, warning = it.action == CHARGEBACK_WARNING,
                )
            },
            pendingRefunds = transaction.adjustments.filter { it.pending && it.action == REFUND }.map { ProviderRefund(it.id, PaymentAmount(it.total, transaction.currency), it.createdAt ?: transaction.billedAt ?: time.now()) },
        )
    }

    /**
     * 환불 한 건을 adjustment 로 만든다. 요청 금액이 거래 총액이고 승인 · 검토 중 환불이 없으면 `type: full`, 아니면 `partial` + 품목 하나(서버가 만든 거래는 품목이 하나다 - 둘 이상이면 확정 거절 `PARTIAL_NOT_ALLOWED`).
     * 금액은 구매자가 낸 세금 포함 총액 그대로다(`tax_mode` 를 보내지 않는다 - 기본 `internal`). `reason` 은 장부의 고정 문구와 환불 줄 id 뿐이다(개인정보 없음).
     * 결과: `approved` → 환불됨, `pending_approval` → 검토 중(번호를 돌려준다), `rejected` → 실패. 읽을 수 없는 답은 던진다(모름).
     */
    override fun refund(request: PaymentRefundRequest): PaymentOperationResult {
        val id = request.providerPaymentId
        val transaction = transaction(id)
        if (transaction.currency != request.amount.normalizedCurrency) throw refusal(CURRENCY_MISMATCH)
        val held = transaction.adjustments.filter { (it.approved && it.action in TAKEN_ACTIONS) || (it.pending && it.action == REFUND) }
        val whole = held.isEmpty() && request.amount.amount == transaction.total
        val body = linkedMapOf<String, Any?>("action" to REFUND, "transaction_id" to id, "reason" to reasonOf(request), "type" to if (whole) "full" else "partial")
        if (!whole) {
            val item = transaction.lineItems.singleOrNull() ?: throw refusal(PARTIAL_NOT_ALLOWED)
            body["items"] = listOf(mapOf("item_id" to item, "type" to "partial", "amount" to request.amount.amount.toString()))
        }
        val response = requireNotNull(
            httpClient.post(
                clientName = properties.clientName,
                path = "/adjustments",
                body = body,
                responseType = String::class.java,
            ) {
                baseUrl(properties.resolvedBaseUrl)
                header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
                timeout(properties.refundTimeout)
                loggingTag("payment.paddle.refund")
                errorMapper(refundMapper)
            }.block(),
        )
        val data = read(response)?.get("data") ?: throw unreadable()
        val adjustmentId = data.text("id") ?: throw unreadable()
        val status = data.text("status")
        val total = data["totals"]?.text("total")?.toLongOrNull() ?: throw unreadable()
        return PaymentOperationResult(
            provider = providerId,
            providerPaymentId = id,
            status = when (status) {
                "approved" -> PaymentOperationStatus.REFUNDED
                "pending_approval" -> PaymentOperationStatus.PENDING
                "rejected" -> PaymentOperationStatus.FAILED
                else -> PaymentOperationStatus.UNKNOWN
            },
            amount = PaymentAmount(total, transaction.currency),
            trace = ProviderTrace(provider = providerId, providerOperationId = adjustmentId, rawStatus = status),
        )
    }

    override fun refundDeclineCategory(providerCode: String?): RefundDeclineCategory =
        when (providerCode) {
            PARTIAL_NOT_ALLOWED, "adjustment_transaction_item_invalid", "adjustment_invalid_combination_of_types" -> RefundDeclineCategory.PARTIAL_NOT_ALLOWED
            else -> RefundDeclineCategory.OTHER
        }

    private class Adjustment(val id: String, val action: String, val status: String, val total: Long, val createdAt: Instant?) {
        val approved: Boolean get() = status == "approved"
        val pending: Boolean get() = status == "pending_approval"
    }

    private class PaddleTransaction(val status: String, val currency: String, val total: Long, val billedAt: Instant?, val lineItems: List<String>, val adjustments: List<Adjustment>)

    private fun transaction(id: String): PaddleTransaction {
        val response = requireNotNull(
            httpClient.get(
                clientName = properties.clientName,
                path = "/transactions/{id}",
                responseType = String::class.java,
            ) {
                baseUrl(properties.resolvedBaseUrl)
                uriVariable("id", id)
                queryParam("include", "adjustments")
                header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
                timeout(properties.lookupTimeout)
                loggingTag("payment.paddle.lookup")
                errorMapper(lookupMapper)
            }.block(),
        )
        val data = read(response)?.get("data") ?: throw unreadable()
        if (data.text("id") != id) throw unreadable() // 물은 거래가 맞는지 확인한다
        val totals = data["details"]?.get("totals")
        return PaddleTransaction(
            status = data.text("status") ?: throw unreadable(),
            currency = (data.text("currency_code") ?: totals.text("currency_code"))?.uppercase() ?: throw unreadable(),
            total = totals.text("total")?.toLongOrNull() ?: throw unreadable(),
            billedAt = data.text("billed_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            lineItems = data["details"]?.get("line_items")?.toList().orEmpty().mapNotNull { it.text("id") },
            adjustments = data["adjustments"]?.toList().orEmpty().map {
                Adjustment(
                    id = it.text("id") ?: throw unreadable(), action = it.text("action") ?: throw unreadable(), status = it.text("status") ?: throw unreadable(),
                    total = it["totals"]?.text("total")?.toLongOrNull() ?: throw unreadable(), createdAt = it.text("created_at")?.let { at -> runCatching { Instant.parse(at) }.getOrNull() },
                )
            },
        )
    }

    private fun reasonOf(request: PaymentRefundRequest): String =
        listOfNotNull(request.reason?.takeIf { it.isNotBlank() }, request.idempotencyKey?.value).joinToString(" ").ifBlank { "Refund" }.take(REASON_MAX)

    /** 우리가 먼저 거절하는 환불(Paddle 을 부르지 않는다) - 돈이 움직이지 않았으니 확정이다. */
    private fun refusal(code: String): PaymentProviderException =
        PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId, code = code, message = null, trace = ProviderTrace(provider = providerId, rawCode = code), upstreamStatus = 422, retryable = false, definitive = true,
            ),
            clientName = properties.clientName,
            method = "POST",
            uri = java.net.URI.create(properties.resolvedBaseUrl),
        )

    /** 읽을 수 없는 답 - 모름이다(확정이 아니다). */
    private fun unreadable(): PaymentProviderException =
        PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId, code = UNREADABLE_RESPONSE, message = null, trace = ProviderTrace(provider = providerId, rawCode = UNREADABLE_RESPONSE), upstreamStatus = 200, retryable = true,
            ),
            clientName = properties.clientName,
            method = "HTTP",
            uri = java.net.URI.create(properties.resolvedBaseUrl),
        )

    private fun read(text: String): JsonNode? = try {
        mapper.readTree(text)?.takeIf { it.isObject }
    } catch (_: Exception) {
        null
    }

    private fun JsonNode?.text(name: String): String? = this?.get(name)?.takeIf { it.isString || it.isNumber }?.asString()?.takeIf { it.isNotBlank() }

    private companion object {
        const val KIND = "PADDLE_CHECKOUT"
        val SUBSCRIPTION_EVENTS = setOf("subscription.created", "subscription.activated", "subscription.updated", "subscription.canceled", "subscription.past_due")
        val ADJUSTMENT_EVENTS = setOf("adjustment.created", "adjustment.updated")
        val HANDLED = SUBSCRIPTION_EVENTS + ADJUSTMENT_EVENTS + setOf("transaction.completed", "transaction.payment_failed")
        const val REFUND = "refund"
        const val CHARGEBACK = "chargeback"
        const val CHARGEBACK_WARNING = "chargeback_warning"

        /** 구매자에게 돌아간 돈으로 세는 adjustment 종류(이뤄진 것만) - 환불과 차지백(경고 포함: 이의 금액은 환불된다). */
        val TAKEN_ACTIONS = setOf(REFUND, CHARGEBACK, CHARGEBACK_WARNING)
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
        const val PARTIAL_NOT_ALLOWED = "PARTIAL_NOT_ALLOWED"
        const val CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
        const val UNREADABLE_RESPONSE = "UNREADABLE_RESPONSE"
        const val REASON_MAX = 200
    }
}
