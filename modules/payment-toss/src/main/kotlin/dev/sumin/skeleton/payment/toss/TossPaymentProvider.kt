package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.common.http.ExternalHttpResponse
import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.BillingIssue
import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentBillingGateway
import dev.sumin.skeleton.payment.PaymentCancelRequest
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentEasyPayGateway
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentProvider
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentSnapshot
import dev.sumin.skeleton.payment.PaymentWebhookGateway
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.RefundDeclineCategory
import dev.sumin.skeleton.payment.ProviderTrace
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookEnvelope
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * 토스페이먼츠(결제창 + API 개별 연동 키). 한 빈이 승인 · 환불 · 조회 · 결제창 값 · 웹훅 해석 · 자동결제(빌링)를 모두 맡는다(라우터에 결제사가 하나로 보이게).
 *
 * **응답 본문은 어디에도 싣지 않는다**: 응답은 아래 [TossPaymentResponse] 가 선언한 칸만 읽고(나머지 — 구매자 이름 · 이메일 · 전화 · 카드 상세 — 는 버려진다),
 * 오류는 코드만 예외에 담으며([TossPaymentErrorMapper]), 읽지 못한 응답은 본문을 인용하지 않는 [PaymentProviderException] 으로 바꾼다.
 */
class TossPaymentProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: TossPaymentProperties,
    /** 인증 계열 거절(키 폐기 · 오타)을 주인에게 알린다 — 없으면 알리지 않는다. */
    private val credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
) : PaymentProvider, PaymentLookupGateway, PaymentCheckoutGateway, PaymentWebhookGateway, PaymentBillingGateway, PaymentEasyPayGateway {
    override val providerId: String = properties.providerId
    override val supportedCurrencies: Set<String> = properties.supportedCurrencies
    override val supportedCountries: Set<String> = properties.supportedCountries

    /** 시크릿 키 접두사: `test_` → TEST · `live_` → LIVE. */
    override val mode: PaymentMode = TossApiKey.parse(properties.secretKey)?.mode ?: PaymentMode.UNKNOWN

    /** 토스의 결제 이벤트에는 서명이 없다 — 주소의 비밀 조각이 필수다(스펙 §6.5). */
    override val signed: Boolean = false

    private val errorMapper = TossPaymentErrorMapper(providerId = providerId, credentialAlert = credentialAlert)

    /** 조회용: 조회의 오류는 확정으로 표시하지 않는다(`definitive` 는 늘 false). */
    private val lookupErrorMapper = TossPaymentErrorMapper(providerId = providerId, definitiveAllowed = false, credentialAlert = credentialAlert)
    private val webhookMapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    init {
        require(providerId.isNotBlank()) { "Toss payment providerId must not be blank" }
        require(properties.secretKey.isNotBlank()) { "Toss payment secretKey must not be blank" }
    }

    /**
     * 장부가 선점 전에 묻는다([dev.sumin.skeleton.payment.PaymentConfirmGateway.acceptsPaymentKey]) — [TossIdentifiers] 의 규칙 그대로.
     * 결제 키의 문자 집합(영문 · 숫자 · `-` · `_`, 최대 200자)은 문서를 읽고 세운 **가정**이다 — 아직 못 쟀다(`docs/real-provider-setup.md` §11.6 「못 잰 가정」). 틀리면 모든 결제가 400 으로 거절되므로
     * 실측 전에는 이 규칙이 진짜 토스의 키를 막지 않는지 먼저 본다.
     */
    override fun acceptsPaymentKey(providerPaymentId: String): Boolean = TossIdentifiers.isPaymentKey(providerPaymentId)

    override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
        val paymentKey = TossIdentifiers.paymentKey(request.providerPaymentId)
        val orderId = request.merchantReferenceId?.takeIf { it.isNotBlank() }?.let(TossIdentifiers::orderId)
        return guarded("confirm") { seen ->
            // 멱등 키 = 주문 id (스펙 §6.6). 호출자가 따로 주면 그것이 이긴다.
            val idempotencyKey = request.idempotencyKey ?: orderId?.let(::IdempotencyKey)
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = properties.confirmPath,
                    body = confirmBody(request, paymentKey, orderId),
                    responseType = TossPaymentResponse::class.java,
                ) {
                    baseUrl(properties.baseUrl)
                    commonHeaders(idempotencyKey)
                    loggingTag("payment.toss.confirm")
                    errorMapper(errorMapper)
                }.block(),
            )
            seen.status = response.statusCode
            paymentResult(response)
        }
    }

    /**
     * 승인 계열 응답(결제 승인 · 빌링 승인) → 결과. 응답에 있는 값만 쓴다 — 키 · 총액 · 통화가 하나라도 없으면 요청 값으로 메우지 않고 읽을 수 없는 응답이다(모름).
     */
    private fun paymentResult(response: ExternalHttpResponse<TossPaymentResponse>): PaymentOperationResult {
        val body = response.body
        val key = body.paymentKey.need("paymentKey")
        val total = body.totalAmount.need("totalAmount")
        val currency = body.currency.need("currency")
        return PaymentOperationResult(
            provider = providerId,
            providerPaymentId = key,
            status = body.status.toConfirmStatus(),
            amount = PaymentAmount(total, currency),
            trace = body.trace(response, operationId = body.lastTransactionKey ?: body.transactionKey),
            attributes = listOfNotNull(body.method.methodCode()?.let { "method" to it }).toMap(),
            card = body.card.toCard(),
            paidAt = body.approvedAt.toInstantOrNull(),
        )
    }

    override fun cancel(request: PaymentCancelRequest): PaymentOperationResult {
        val paymentKey = TossIdentifiers.paymentKey(request.providerPaymentId)
        return guarded("cancel") { seen ->
            val response = postCancel(paymentKey, request.amount, request.reason, request.idempotencyKey, request.providerPayload, "payment.toss.cancel")
            seen.status = response.statusCode
            val body = response.body
            val thisCancel = body.thisCancel()
            val status = body.status.toCancelStatus(thisCancel)
            PaymentOperationResult(
                provider = providerId,
                providerPaymentId = body.paymentKey ?: paymentKey,
                status = status,
                amount = cancelAmountOf(body, thisCancel, status, request.amount),
                trace = body.trace(response, operationId = thisCancel?.transactionKey.takeIf { status == PaymentOperationStatus.CANCELED }),
                attributes = listOfNotNull(body.method.methodCode()?.let { "method" to it }).toMap(),
            )
        }
    }

    /**
     * 환불. 결과는 **응답 상태**로 정하되, 환불로 읽으려면 이 호출이 만든 취소를 응답에서 **근거로** 찾아야 한다:
     * 결제의 `lastTransactionKey` 와 같은 거래 키를 가진 `cancels[]` 항목이 이 환불이다(위치로 추측하지 않는다 — 앞선 환불이 목록의 어디에 있든 상관없다).
     * 못 찾거나 아직 `DONE` 이 아니면 모름(`UNKNOWN`) — 거래 키 · 금액은 비우고 금액은 요청한 값을 돌려준다. 조회가 가린다.
     * `CANCELED`/`PARTIAL_CANCELED` + 이 취소가 `DONE` → `REFUNDED`, 진행 중 → `PENDING`, 실패 → `FAILED`, 그 밖 → `UNKNOWN`.
     * `remaining` 은 응답의 `balanceAmount`. 일부 환불이면 `attributes["partial"]="true"` — `PaymentOperationStatus` 에 일부 환불 값이 없어 `remaining` 과 함께 장부가 읽는다.
     *
     * **멱등 키가 없는 환불도 거절하지 않는다**(기존 `PaymentService` 경로 호환). 그때는 `Idempotency-Key` 헤더 없이 나가고 토스는 같은 요청을 새 요청으로 처리한다 —
     * 다시 보내면 두 번 환불될 수 있으므로 호출자(장부)는 늘 키를 싣는다.
     */
    override fun refund(request: PaymentRefundRequest): PaymentOperationResult {
        val paymentKey = TossIdentifiers.paymentKey(request.providerPaymentId)
        return guarded("refund") { seen ->
            val response = postCancel(paymentKey, request.amount, request.reason, request.idempotencyKey, request.providerPayload, "payment.toss.refund")
            seen.status = response.statusCode
            val body = response.body
            val thisCancel = body.thisCancel()
            val status = body.status.toRefundStatus(thisCancel)
            val currency = body.currency ?: request.amount.currency

            PaymentOperationResult(
                provider = providerId,
                providerPaymentId = body.paymentKey ?: paymentKey,
                status = status,
                amount = cancelAmountOf(body, thisCancel, status, request.amount) ?: request.amount,
                trace = body.trace(response, operationId = thisCancel?.transactionKey.takeIf { status == PaymentOperationStatus.REFUNDED }),
                attributes = listOfNotNull(
                    body.method.methodCode()?.let { "method" to it },
                    ("partial" to "true").takeIf { body.status?.uppercase() == "PARTIAL_CANCELED" && status == PaymentOperationStatus.REFUNDED },
                ).toMap(),
                remaining = body.balanceAmount?.let { PaymentAmount(it, currency) },
            )
        }
    }

    /**
     * 확정 거절([TossPaymentErrorMapper.DEFINITIVE_CODES]의 환불 코드)을 `REFUND_DECLINED` 의 분류로: 일부 환불 불가 · 환불 기간 지남. 그 밖의 확정 거절(요청 · 인증 오류 …)은 [RefundDeclineCategory.OTHER].
     * `ALREADY_*` · `NOT_CANCELABLE_*` · `EXCEED_CANCEL_LIMIT` 은 앞선 요청의 결과일 수 있어 확정이 아니다(모름) — 조회 정산이 가리므로 여기에 오지 않는다.
     */
    override fun refundDeclineCategory(providerCode: String?): RefundDeclineCategory =
        when (providerCode) {
            "NOT_ALLOWED_PARTIAL_REFUND", "NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT" -> RefundDeclineCategory.PARTIAL_NOT_ALLOWED
            "EXCEED_MAX_REFUND_DUE" -> RefundDeclineCategory.PERIOD_EXPIRED
            else -> RefundDeclineCategory.OTHER
        }

    /**
     * 확정 거절([TossPaymentErrorMapper.DEFINITIVE_CODES]의 승인 코드)을 `DECLINED` 의 분류로: 카드사 · 카드 상태 거절 → [ConfirmDeclineCategory.CARD_DECLINED],
     * 한도 초과 → [ConfirmDeclineCategory.LIMIT_OR_BALANCE]. 그 밖(요청 · 인증 오류 · 모르는 코드)은 [ConfirmDeclineCategory.OTHER]. 일시 장애는 확정 거절이 아니라(모름) 여기에 오지 않는다.
     */
    override fun confirmDeclineCategory(providerCode: String?): ConfirmDeclineCategory =
        when (providerCode) {
            "REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "REJECT_ACCOUNT_PAYMENT", "INVALID_REJECT_CARD", "INVALID_STOPPED_CARD",
            "INVALID_CARD_LOST_OR_STOLEN", "INVALID_CARD_NUMBER", "INVALID_CARD_EXPIRATION", "FDS_ERROR",
            -> ConfirmDeclineCategory.CARD_DECLINED
            "EXCEED_MAX_AMOUNT", "EXCEED_MAX_PAYMENT_AMOUNT", "EXCEED_MAX_DAILY_PAYMENT_COUNT", "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT" -> ConfirmDeclineCategory.LIMIT_OR_BALANCE
            else -> ConfirmDeclineCategory.OTHER
        }

    // --- 자동결제(빌링) — 스펙 `2026-10-09-subscription-design.md` §6. 토스가 스케줄링을 하지 않으므로 등록 · 승인 · 삭제뿐이다. ---

    override fun billingSetup(customerKey: String): BillingSetup? {
        val clientKey = publicClientKey() ?: return null
        return BillingSetup(
            provider = providerId,
            kind = BILLING_KIND,
            testMode = mode == PaymentMode.TEST,
            clientKey = clientKey,
            customerKey = customerKey,
            attributes = mapOf("method" to "CARD"),
        )
    }

    override fun issue(customerKey: String, providerPayload: Map<String, Any?>): BillingIssue {
        val authKey = TossIdentifiers.authKey(providerPayload["authKey"] as? String ?: throw TossIdentifierRejectedException())
        val customer = TossIdentifiers.customerKey(customerKey)
        return guarded("billing issue") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = BILLING_ISSUE_PATH,
                    body = mapOf("authKey" to authKey, "customerKey" to customer),
                    responseType = TossBillingResponse::class.java,
                ) {
                    baseUrl(properties.baseUrl)
                    commonHeaders(null)
                    loggingTag("payment.toss.billing-issue")
                    errorMapper(errorMapper)
                }.block(),
            )
            seen.status = response.statusCode
            val issued = response.body.billingKey
            // 받았는데 모양이 우리 규칙 밖이면 토스에 살아 있는 카드를 남기지 않게 그 자리에서 지우려 해 본다(경로에는 인코딩해 넣는다)
            if (issued != null && !TossIdentifiers.isBillingKey(issued)) runCatching { revokeRaw(issued) }
            BillingIssue(
                token = issued?.takeIf(TossIdentifiers::isBillingKey).need("billingKey"),
                card = response.body.card.toCard(),
            )
        }
    }

    override fun charge(request: BillingChargeRequest): PaymentOperationResult {
        val billingKey = TossIdentifiers.billingKey(request.token)
        val customer = TossIdentifiers.customerKey(request.customerKey)
        val orderId = TossIdentifiers.orderId(request.orderId)
        return guarded("billing charge") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = BILLING_PATH,
                    body = mapOf(
                        "customerKey" to customer,
                        "amount" to request.amount.amount,
                        "orderId" to orderId,
                        "orderName" to request.orderName,
                    ),
                    responseType = TossPaymentResponse::class.java,
                ) {
                    baseUrl(properties.baseUrl)
                    uriVariable("billingKey", billingKey)
                    commonHeaders(request.idempotencyKey)
                    loggingTag("payment.toss.billing-charge")
                    errorMapper(errorMapper)
                }.block(),
            )
            seen.status = response.statusCode
            paymentResult(response)
        }
    }

    private fun revokeRaw(billingKey: String) {
        httpClient.deleteResponse(properties.clientName, BILLING_PATH, Map::class.java) {
            baseUrl(properties.baseUrl)
            uriVariable("billingKey", billingKey)
            commonHeaders(null)
            loggingTag("payment.toss.billing-revoke")
            errorMapper(errorMapper)
        }.block()
    }

    /** 이미 없는 빌링키(`INVALID_BILL_KEY_REQUEST` · `NOT_FOUND_BILLING`)는 정상이다 — 지우려던 결과가 이미 그렇다. 그 밖의 실패는 던진다. */
    override fun revoke(customerKey: String, token: String) {
        val billingKey = TossIdentifiers.billingKey(token)
        guarded("billing revoke") { seen ->
            try {
                httpClient.deleteResponse(properties.clientName, BILLING_PATH, Map::class.java) {
                    baseUrl(properties.baseUrl)
                    uriVariable("billingKey", billingKey)
                    commonHeaders(null)
                    loggingTag("payment.toss.billing-revoke")
                    errorMapper(errorMapper)
                }.block()?.let { seen.status = it.statusCode }
            } catch (e: PaymentProviderException) {
                if (e.providerError.code !in BILLING_GONE_CODES) throw e
            }
        }
    }

    /** 빌링 승인의 거절 분류는 결제 승인과 같은 코드 어휘다. */
    override fun billingDeclineCategory(providerCode: String?): ConfirmDeclineCategory = confirmDeclineCategory(providerCode)

    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? {
        val paymentKey = query.providerPaymentId?.takeIf { it.isNotBlank() }?.let(TossIdentifiers::paymentKey)
        val orderId = if (paymentKey == null) TossIdentifiers.orderId(requireNotNull(query.orderId)) else null
        return guarded("lookup") { seen ->
            val response = try {
                httpClient.getResponse(
                    clientName = properties.clientName,
                    path = if (paymentKey != null) properties.lookupPath else properties.lookupByOrderPath,
                    responseType = TossPaymentResponse::class.java,
                ) {
                    baseUrl(properties.baseUrl)
                    if (paymentKey != null) uriVariable("paymentKey", paymentKey) else uriVariable("orderId", requireNotNull(orderId))
                    commonHeaders(null)
                    loggingTag("payment.toss.lookup")
                    // 조회의 오류는 돈에 대해 아무것도 말하지 않는다 — 확정으로 읽히지 않게 한다(404 "없는 결제"만 아래에서 null)
                    errorMapper(lookupErrorMapper)
                    timeout(properties.lookupTimeout)
                }.block()
            } catch (e: PaymentProviderException) {
                if (e.isNoSuchPayment()) return@guarded null
                throw e
            }
            val answer = requireNotNull(response)
            seen.status = answer.statusCode
            // 물은 결제가 맞는지 확인한다 — 다른 결제의 답을 이 주문의 사실로 읽지 않는다
            if (paymentKey != null && answer.body.paymentKey != paymentKey) throw UnreadableAnswer("paymentKey")
            if (orderId != null && answer.body.orderId != orderId) throw UnreadableAnswer("orderId")
            answer.body.toSnapshot()
        }
    }

    /** 공개 클라이언트 키를 낼 수 없으면(비었거나 모양 · 모드가 시크릿 키와 다름) 결제창이 열리지 않는다. */
    override fun canOpenCheckout(): Boolean = publicClientKey() != null

    override fun easyPays(): List<String> = TossEasyPays.normalize(properties.easyPays)

    override fun checkout(order: CheckoutOrder): CheckoutInstruction =
        CheckoutInstruction(
            provider = providerId,
            kind = CHECKOUT_KIND,
            testMode = mode == PaymentMode.TEST,
            orderId = order.orderId,
            orderName = order.orderName,
            amount = order.amount,
            clientKey = publicClientKey(),
            // SDK v2 requestPayment 의 고정 인자. 구매자 이름 · 이메일 · 전화번호는 넘기지 않는다(선택 칸).
            // 비회원 구매자 키는 SDK 의 상수(`TossPayments.ANONYMOUS`)라 서버가 문자열로 넘겨 주지 않는다.
            // 간편결제 목록이 비면 지금과 같다(`method` 하나). 있으면 쉼표로 이어 프론트가 단추를 하나씩 그린다.
            attributes = mapOf("method" to "CARD") + easyPays().takeIf { it.isNotEmpty() }?.let { mapOf("easyPays" to it.joinToString(",")) }.orEmpty(),
        )

    /**
     * 브라우저에 내려가는 키다 — **클라이언트 키(`ck`) 모양이고 시크릿 키와 같은 모드일 때만** 준다. 시크릿 키 · 위젯 키 · 모양이 다른 값을
     * 잘못 끼웠으면 내보내지 않는다(null — 결제창이 열리지 않는다고 알리게 한다. 기동 때는 [TossKeyGuard] 가 막는다).
     */
    private fun publicClientKey(): String? {
        val raw = properties.clientKey.trim()
        val key = TossApiKey.parse(raw) ?: return null
        if (key.kind != TossApiKey.Kind.CLIENT) return null
        if (mode != PaymentMode.UNKNOWN && key.mode != mode) return null
        return raw
    }

    /**
     * 결제 이벤트에는 서명이 없다. **본문의 상태 · 금액은 읽지 않는다** — 결제 키와 주문 번호만 꺼내고 사실은 조회에서 온다(스펙 §6.5).
     * 결제 상태 이벤트(`PAYMENT_STATUS_CHANGED`) 외에는 `resourceType="unsupported"` 로 돌려준다(1단계에서는 무시되는 이벤트).
     * 읽을 수 없거나 결제 이벤트가 아닌 본문은 고정 문장의 [PaymentWebhookRejectedException] (본문을 인용하지 않는다).
     *
     * **가정 — 아직 못 쟀다** (`docs/real-provider-setup.md` §11.6 「못 잰 가정」 · 스펙 §16 · §17: 공식 문서에서 확정하지 못했다):
     * - 이벤트 키(수신함의 `(provider, event_key)` 유니크)를 헤더 `tosspayments-webhook-transmission-id` 에서 읽는다. 그 헤더가 결제 이벤트에도 오는지, **재전송에서 같은 값인지**는 모른다.
     *   같은 값이면 재전송이 한 줄로 합쳐지고, 다르면 같은 일로 줄이 여럿 생길 뿐이다(처리는 늘 "조회해서 맞춘다"라 결과는 같다). 헤더가 없거나 모양이 낯설면 본문 sha256 이다.
     * - `PAYMENT_STATUS_CHANGED` 의 `data.paymentKey` · `data.orderId` 위치. 모양이 다르면 결제 키를 못 꺼내 거절하고(토스가 다시 보낸다) 수신함에 닿지 못한다.
     * 가정이 틀려도 안전한 쪽으로 틀린다 — 본문의 상태 · 금액은 어느 경우에도 읽지 않는다.
     */
    override fun parse(delivery: WebhookDelivery): WebhookEnvelope {
        val body = try {
            webhookMapper.readValue(delivery.body, TossWebhookBody::class.java)
        } catch (_: Exception) {
            null
        } ?: throw PaymentWebhookRejectedException() // JSON `null` 도 이벤트가 아니다
        val eventType = body.eventType?.takeIf { SAFE_EVENT_TYPE.matches(it) } ?: throw PaymentWebhookRejectedException()
        val eventKey = delivery.headers[TRANSMISSION_ID_HEADER]?.trim()?.takeIf { SAFE_EVENT_KEY.matches(it) } ?: sha256Hex(delivery.body)

        if (eventType != PAYMENT_STATUS_CHANGED) {
            return WebhookEnvelope(eventKey = eventKey, eventType = eventType, resourceType = UNSUPPORTED_RESOURCE, resourceId = eventKey)
        }
        val paymentKey = body.data?.paymentKey?.takeIf { TossIdentifiers.isPaymentKey(it) }
            ?: throw PaymentWebhookRejectedException()
        return WebhookEnvelope(
            eventKey = eventKey,
            eventType = eventType,
            resourceType = PAYMENT_RESOURCE,
            resourceId = paymentKey,
            orderId = body.data.orderId?.takeIf { SAFE_ID.matches(it) },
            storedPayload = null,
        )
    }

    private fun postCancel(
        providerPaymentId: String,
        amount: PaymentAmount?,
        reason: String?,
        idempotencyKey: IdempotencyKey?,
        providerPayload: Map<String, Any?>,
        loggingTag: String,
    ): ExternalHttpResponse<TossPaymentResponse> =
        requireNotNull(
            httpClient.postResponse(
                clientName = properties.clientName,
                path = properties.cancelPath,
                body = cancelBody(amount, reason, providerPayload),
                responseType = TossPaymentResponse::class.java,
            ) {
                baseUrl(properties.baseUrl)
                uriVariable("paymentKey", providerPaymentId)
                commonHeaders(idempotencyKey)
                loggingTag(loggingTag)
                errorMapper(errorMapper)
            }.block(),
        )

    private fun confirmBody(request: PaymentConfirmRequest, paymentKey: String, orderId: String?): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "paymentKey" to paymentKey,
            "amount" to request.amount.amount,
        ).apply {
            orderId?.let { put("orderId", it) }
            putAll(request.providerPayload)
        }

    private fun cancelBody(
        amount: PaymentAmount?,
        reason: String?,
        providerPayload: Map<String, Any?>,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            reason?.takeIf { it.isNotBlank() }?.let { put("cancelReason", it) }
            amount?.let {
                put("cancelAmount", it.amount)
                put("currency", it.normalizedCurrency)
            }
            putAll(providerPayload)
        }

    private fun ExternalHttpRequestSpec.commonHeaders(idempotencyKey: IdempotencyKey?) {
        header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        vendorTraceHeaders(*TossPaymentErrorMapper.TOSS_TRACE_HEADERS.toTypedArray())
        idempotencyKey?.let { header("Idempotency-Key", it.value) }
    }

    private fun basicAuthorization(): String {
        val token = Base64.getEncoder()
            .encodeToString("${properties.secretKey.trim()}:".toByteArray(StandardCharsets.UTF_8))
        return "Basic $token"
    }

    /** 추적 번호 · 원 상태는 코드 모양일 때만 싣는다(낯선 문자열은 버린다). */
    private fun TossPaymentResponse.trace(response: ExternalHttpResponse<*>, operationId: String?): ProviderTrace =
        ProviderTrace(
            provider = providerId,
            providerRequestId = listOfNotNull(traceId, response.trace.traceId, response.headers.tossTraceId()).firstOrNull(SAFE_TRACE::matches),
            providerOperationId = operationId?.takeIf(TRANSACTION_KEY::matches),
            rawStatus = status?.takeIf(SAFE_STATUS::matches),
            metadata = listOfNotNull(orderId?.takeIf(SAFE_ID::matches)?.let { "orderId" to it }).toMap(),
        )

    /** 이 호출이 만든 취소: 결제의 `lastTransactionKey` 와 같은 거래 키를 가진 `cancels[]` 의 항목 하나. 근거가 없으면 null(위치로 추측하지 않는다). */
    private fun TossPaymentResponse.thisCancel(): TossCancel? {
        val last = lastTransactionKey?.takeIf(TRANSACTION_KEY::matches) ?: return null
        return cancels.orEmpty().filter { it.transactionKey == last }.singleOrNull()
    }

    /** 이 취소가 끝난(`DONE`) 것으로 확인될 때만 그 취소의 금액. 아니면 요청한 값. */
    private fun cancelAmountOf(body: TossPaymentResponse, thisCancel: TossCancel?, status: PaymentOperationStatus, asked: PaymentAmount?): PaymentAmount? {
        val done = thisCancel?.takeIf { it.isDone() && (status == PaymentOperationStatus.REFUNDED || status == PaymentOperationStatus.CANCELED) }
        val cancelAmount = done?.cancelAmount ?: return asked
        return PaymentAmount(cancelAmount, body.currency ?: asked?.currency ?: return asked)
    }

    /** 조회 응답 → 중립 스냅샷. 읽을 수 없거나 모르는 값이거나 서로 어긋나면 던진다(추측하지 않는다) — 호출한 쪽에서는 모름으로 읽힌다. */
    private fun TossPaymentResponse.toSnapshot(): PaymentSnapshot {
        val currency = currency.need("currency")
        val total = totalAmount.need("totalAmount")
        val snapshotStatus = status.toSnapshotStatus().need("status")
        // 상태가 비어 있는 취소는 조용히 빼지 않고 읽을 수 없는 응답으로 본다
        val done = cancels.orEmpty().filter { it.cancelStatus.need("cancelStatus").isNotBlank() && it.isDone() }
        // 상태와 환불 목록이 어긋나면 읽을 수 없는 응답이다
        val refunded = snapshotStatus == ProviderPaymentStatus.REFUNDED || snapshotStatus == ProviderPaymentStatus.PARTIALLY_REFUNDED
        if (refunded && done.isEmpty()) throw UnreadableAnswer("cancels")
        if (snapshotStatus == ProviderPaymentStatus.PAID && done.isNotEmpty()) throw UnreadableAnswer("cancels")
        val refunds = done.map { cancel ->
            ProviderRefund(
                providerRefundId = cancel.transactionKey?.takeIf(TRANSACTION_KEY::matches).need("transactionKey"),
                amount = PaymentAmount(cancel.cancelAmount.need("cancelAmount"), currency),
                at = cancel.canceledAt.toInstantOrNull().need("canceledAt"),
            )
        }
        val remaining = balanceAmount ?: (total - refunds.sumOf { it.amount.amount })
        return PaymentSnapshot(
            providerPaymentId = paymentKey?.takeIf(TossIdentifiers::isPaymentKey).need("paymentKey"),
            orderId = orderId?.takeIf(SAFE_ID::matches),
            status = snapshotStatus,
            total = PaymentAmount(total, currency),
            remaining = PaymentAmount(remaining, currency),
            method = method.methodCode(),
            paidAt = approvedAt.toInstantOrNull(),
            refunds = refunds,
            card = card.toCard(),
        )
    }

    private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun <T : Any> T?.need(field: String): T = this ?: throw UnreadableAnswer(field)

    private fun TossCard?.toCard(): PaymentCard? {
        if (this == null) return null
        val issuer = issuerCode?.trim()?.takeIf { it.isNotEmpty() }?.let { code -> CARD_ISSUERS[code.uppercase()] ?: code.takeIf(SAFE_ISSUER::matches) }
        val last4 = number?.takeIf { it.length >= 4 }?.takeLast(4)?.takeIf(LAST4::matches)
        return if (issuer == null && last4 == null) null else PaymentCard(issuer = issuer, last4 = last4)
    }

    private fun String?.toSnapshotStatus(): ProviderPaymentStatus? =
        when (this) {
            "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> ProviderPaymentStatus.IN_PROGRESS
            "DONE" -> ProviderPaymentStatus.PAID
            "PARTIAL_CANCELED" -> ProviderPaymentStatus.PARTIALLY_REFUNDED
            "CANCELED" -> ProviderPaymentStatus.REFUNDED
            "ABORTED" -> ProviderPaymentStatus.FAILED
            "EXPIRED" -> ProviderPaymentStatus.EXPIRED
            else -> null
        }

    private fun String?.toConfirmStatus(): PaymentOperationStatus =
        when (this?.uppercase()) {
            "DONE", "CONFIRMED" -> PaymentOperationStatus.CONFIRMED
            "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> PaymentOperationStatus.PENDING
            "CANCELED", "PARTIAL_CANCELED" -> PaymentOperationStatus.CANCELED
            "ABORTED", "EXPIRED" -> PaymentOperationStatus.FAILED
            else -> PaymentOperationStatus.UNKNOWN
        }

    /** 취소 호출의 응답 상태. 취소로 읽으려면 이 호출의 취소가 `DONE` 으로 확인돼야 한다. `DONE`(취소가 반영되지 않은 결제 그대로)는 성공이 아니다 — 모름(조회로 가린다). */
    private fun String?.toCancelStatus(thisCancel: TossCancel?): PaymentOperationStatus =
        when (this?.uppercase()) {
            "CANCELED", "PARTIAL_CANCELED" -> if (thisCancel?.isDone() == true) PaymentOperationStatus.CANCELED else PaymentOperationStatus.UNKNOWN
            else -> toInProgressOrFailed()
        }

    /** 환불 호출의 응답 상태. 규칙은 [toCancelStatus] 와 같고, 환불로 읽힌다. */
    private fun String?.toRefundStatus(thisCancel: TossCancel?): PaymentOperationStatus =
        when (this?.uppercase()) {
            "CANCELED", "PARTIAL_CANCELED" -> if (thisCancel?.isDone() == true) PaymentOperationStatus.REFUNDED else PaymentOperationStatus.UNKNOWN
            else -> toInProgressOrFailed()
        }

    private fun String?.toInProgressOrFailed(): PaymentOperationStatus =
        when (this?.uppercase()) {
            "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> PaymentOperationStatus.PENDING
            "ABORTED", "EXPIRED" -> PaymentOperationStatus.FAILED
            else -> PaymentOperationStatus.UNKNOWN
        }

    private fun PaymentProviderException.isNoSuchPayment(): Boolean =
        providerError.upstreamStatus == 404 && providerError.code == NOT_FOUND_PAYMENT

    /** [guarded] 의 블록이 응답을 받으면 그 HTTP 상태를 적어 둔다 — 읽을 수 없는 응답 오류가 실제 상태를 달고 나가게. */
    private class Seen(var status: Int = 200)

    /**
     * 호출과 응답 해석 전체를 감싼다. 결제사가 답한 오류(타임아웃 · 네트워크 · 오류 코드 — [ExternalHttpException])와 호출자 잘못(`ApplicationException`)은 그대로 두고,
     * 그 밖의 모든 실패(깨진 JSON · 빈 응답 · 모르는 상태 · 읽을 수 없는 값)는 **본문을 인용하지 않는** `UNREADABLE_RESPONSE` 로 바꾼다.
     * 응답 해석에서 던진 예외(원인)는 달지 않는다 — 디코더의 예외 메시지는 본문 조각을 담을 수 있다. 로그에는 우리가 정한 사유(칸 이름)나 예외 종류만 남는다.
     * 결과는 모름으로 읽힌다(`PaymentFailures.outcomeOf`).
     */
    private inline fun <T> guarded(operation: String, block: (Seen) -> T): T {
        val seen = Seen()
        return try {
            block(seen)
        } catch (e: ExternalHttpException) {
            throw e
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is InterruptedException }) throw e
            val reason = (e as? UnreadableAnswer)?.reason ?: e.javaClass.simpleName
            log.warn("Toss {} answer could not be read (HTTP status {}, {})", operation, seen.status, reason)
            throw unreadable(seen.status)
        }
    }

    private fun unreadable(upstreamStatus: Int): PaymentProviderException =
        PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = UNREADABLE_RESPONSE,
                message = null,
                trace = ProviderTrace(provider = providerId, rawCode = UNREADABLE_RESPONSE),
                upstreamStatus = upstreamStatus,
                retryable = true,
                rawBody = null,
            ),
            clientName = properties.clientName,
            method = "HTTP",
            uri = runCatching { URI.create(properties.baseUrl.trim()) }.getOrDefault(URI.create("about:blank")),
        )

    /** 응답을 읽을 수 없다는 사유(칸 이름 같은 고정 문자열 — 값은 담지 않는다). 밖으로 나가지 않고 [guarded] 가 로그에 사유만 남긴다. */
    private class UnreadableAnswer(val reason: String) : RuntimeException(reason, null, false, false)

    private fun HttpHeaders.tossTraceId(): String? =
        TossPaymentErrorMapper.TOSS_TRACE_HEADERS.firstNotNullOfOrNull { name ->
            getFirst(name)?.takeIf { it.isNotBlank() }
        }

    /** 토스의 결제 수단 값 → 결제사 중립의 종류 코드. 표에 없는 값(사람 이름 · 전화번호 모양 포함)은 null — 받은 값을 그대로 넘기지 않는다. */
    private fun String?.methodCode(): String? = this?.trim()?.let { METHOD_CODES[it.uppercase()] }

    private fun TossCancel.isDone(): Boolean = cancelStatus == "DONE"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val log = LoggerFactory.getLogger(TossPaymentProvider::class.java)

        const val CHECKOUT_KIND = "TOSS_PAYMENT_WINDOW"
        const val BILLING_KIND = "TOSS_BILLING_AUTH"
        const val BILLING_ISSUE_PATH = "/v1/billing/authorizations/issue"
        const val BILLING_PATH = "/v1/billing/{billingKey}"
        val BILLING_GONE_CODES = setOf("INVALID_BILL_KEY_REQUEST", "NOT_FOUND_BILLING")
        const val NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT"
        const val UNREADABLE_RESPONSE = "UNREADABLE_RESPONSE"
        const val PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED"
        const val PAYMENT_RESOURCE = "payment"
        const val UNSUPPORTED_RESOURCE = "unsupported"
        const val TRANSMISSION_ID_HEADER = "tosspayments-webhook-transmission-id"

        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SAFE_TRACE = Regex("[A-Za-z0-9_.:-]{1,100}")
        val SAFE_EVENT_TYPE = Regex("[A-Za-z0-9_.-]{1,64}")
        val SAFE_EVENT_KEY = Regex("[A-Za-z0-9._:-]{1,200}")
        val SAFE_ISSUER = Regex("[A-Za-z0-9_-]{1,40}")
        /**
         * 토스 `method` 값 → 중립 코드. 한글 라벨(「카드」 등)과 영문 값을 둘 다 받는다. **실제 토스가 보내는 값은 아직 못 쟀다**(`docs/real-provider-setup.md` §11.6 「못 잰 가정」) —
         * 지금 표는 문서의 결제 수단 이름에 기댄 것이다. 표에 없으면 null(장부에는 수단이 비어 남는다).
         */
        val METHOD_CODES = mapOf(
            "카드" to "CARD", "CARD" to "CARD",
            "간편결제" to "EASY_PAY", "EASY_PAY" to "EASY_PAY",
            "가상계좌" to "VIRTUAL_ACCOUNT", "VIRTUAL_ACCOUNT" to "VIRTUAL_ACCOUNT",
            "계좌이체" to "TRANSFER", "TRANSFER" to "TRANSFER",
            "휴대폰" to "MOBILE_PHONE", "MOBILE_PHONE" to "MOBILE_PHONE",
            "문화상품권" to "CULTURE_GIFT_CARD", "CULTURE_GIFT_CARD" to "CULTURE_GIFT_CARD",
            "도서문화상품권" to "BOOK_GIFT_CARD", "BOOK_GIFT_CARD" to "BOOK_GIFT_CARD",
            "게임문화상품권" to "GAME_GIFT_CARD", "GAME_GIFT_CARD" to "GAME_GIFT_CARD",
        )
        val SAFE_STATUS = Regex("[A-Z_]{1,40}")
        val TRANSACTION_KEY = Regex("[A-Za-z0-9_-]{1,64}")
        val LAST4 = Regex("[0-9*]{4}")

        /**
         * 토스 카드사 코드 → 결제사 중립의 대문자 영문 코드. 표에 없는 코드는 받은 그대로 넘어간다.
         * 출처: 토스페이먼츠 카드사 코드표(`card.issuerCode`) — 구현 때 공식 문서 원문으로 다시 대조하지 못했다(docs/modules/payment-toss.md 「검증 기록」).
         */
        val CARD_ISSUERS: Map<String, String> = mapOf(
            "11" to "KOOKMIN",
            "21" to "HANA",
            "24" to "TOSSBANK",
            "15" to "KAKAOBANK",
            "3A" to "KBANK",
            "30" to "KDB",
            "31" to "BC",
            "3K" to "IBK_BC",
            "33" to "WOORI_BC",
            "34" to "SUHYUP",
            "35" to "JEONBUK",
            "36" to "CITI",
            "37" to "POST",
            "38" to "SAEMAUL",
            "39" to "SAVINGS",
            "41" to "SHINHAN",
            "42" to "JEJU",
            "46" to "GWANGJU",
            "51" to "SAMSUNG",
            "61" to "HYUNDAI",
            "62" to "SHINHYUP",
            "71" to "LOTTE",
            "91" to "NONGHYUP",
            "W1" to "WOORI",
            "3C" to "UNIONPAY",
            "4J" to "JCB",
            "4M" to "MASTERCARD",
            "4V" to "VISA",
            "6D" to "DINERS",
            "7A" to "AMEX",
        )
    }
}

/** 토스 결제 객체 중 이 모듈이 읽는 칸만. 선언하지 않은 칸(구매자 이름 · 이메일 · 전화 · 카드 상세 …)은 읽히지 않고 버려진다. */
data class TossPaymentResponse(
    val paymentKey: String? = null,
    val orderId: String? = null,
    val status: String? = null,
    val totalAmount: Long? = null,
    val cancelAmount: Long? = null,
    val currency: String? = null,
    val method: String? = null,
    val traceId: String? = null,
    val transactionKey: String? = null,
    val balanceAmount: Long? = null,
    val approvedAt: String? = null,
    val lastTransactionKey: String? = null,
    val cancels: List<TossCancel>? = null,
    val card: TossCard? = null,
)

data class TossCancel(
    val transactionKey: String? = null,
    val cancelAmount: Long? = null,
    val canceledAt: String? = null,
    val cancelStatus: String? = null,
)

/**
 * 카드 객체에서 읽는 두 칸. `number` 는 가려서 오지만 **끝 네 글자만** 쓴다.
 * 디코더가 디코딩한 객체를 DEBUG 로 찍을 수 있어 `toString` 에는 번호를 싣지 않는다.
 */
data class TossCard(
    val issuerCode: String? = null,
    val number: String? = null,
) {
    override fun toString(): String = "TossCard(issuerCode=$issuerCode, number=${if (number == null) "null" else "***"})"
}

/** 카드 등록 인증 교환(`/issue`)의 응답에서 읽는 칸. 빌링키는 비밀이라 [toString] 이 찍지 않는다. */
data class TossBillingResponse(
    val billingKey: String? = null,
    val card: TossCard? = null,
) {
    override fun toString(): String = "TossBillingResponse(billingKey=<redacted>, card=$card)"
}

internal data class TossWebhookBody(
    val eventType: String? = null,
    val data: TossWebhookData? = null,
)

internal data class TossWebhookData(
    val paymentKey: String? = null,
    val orderId: String? = null,
)
