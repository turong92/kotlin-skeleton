package dev.sumin.skeleton.payment.kakaopay

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.common.http.ExternalHttpResponse
import dev.sumin.skeleton.payment.BillingIssue
import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.BillingPrepareRequest
import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PreparedBilling
import dev.sumin.skeleton.payment.RegisterWithChargeRequest
import dev.sumin.skeleton.payment.RegisteredCharge
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCancelRequest
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
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
import dev.sumin.skeleton.payment.PaymentUnavailableException
import dev.sumin.skeleton.payment.PaymentFailures
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.ProviderTrace
import dev.sumin.skeleton.payment.ReturnOrigins
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.slf4j.LoggerFactory

/**
 * 카카오페이 단건 결제(온라인 결제 REST, 결제 준비 → 구매자 승인 → 서버 승인 → 취소 · 조회). 한 빈이 결제창 값(`ready`) · 승인 · 환불 · 조회를 모두 맡는다(라우터에 결제사가 하나로 보이게).
 * 웹훅은 이 모듈에 없다 - 카카오페이 문서에서 결제 이벤트 웹훅을 찾지 못했다(`docs/modules/payment-kakaopay.md`). 결과는 승인 응답과 주문 조회로 맞춘다.
 * 정기 결제(SID)는 [KakaoPayBillingProvider] 하위 클래스가 능력으로 드러낸다(정기 가맹점 코드 `subscription-cid` 가 있을 때만 그 클래스로 만든다 - 능력은 인터페이스로 선언되므로 빈 자체가 달라야 한다). 이 클래스의 `internal` 정기 걸음을 부른다.
 *
 * **결제창 흐름**: [checkout] 이 `ready` 를 서버에서 부르고(`partner_order_id` = 우리 주문 id) 받은 `tid` 를 [CheckoutInstruction.providerPaymentId] 로 돌려줘 장부가 주문에 박는다. 결제창 주소(PC · 모바일 · 앱)는 checkout 응답에 실려 프론트가 구매자를
 * 보낸다. 구매자가 카카오톡에서 인증을 마치면 카카오페이가 우리 `return-url` 로 `pg_token` 을 붙여 돌려보내고, 프론트가 그 토큰으로 승인을 부른다([confirm] = `approve`). **서버 승인 없이는 돈이 움직이지 않는다**(결제창 결제사의 계약).
 *
 * **응답 본문은 어디에도 싣지 않는다**: 응답은 아래 data class 가 선언한 칸만 읽고(나머지 - 카드 정보 · 상품명 · 회원 id - 는 버려진다), 오류는 코드만 예외에 담으며([KakaoPayErrorMapper]),
 * 읽지 못한 응답은 본문을 인용하지 않는 [PaymentProviderException] 으로 바꾼다.
 */
open class KakaoPayProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: KakaoPayProperties,
    /** 인증 계열 거절(키 폐기 · 오타)을 주인에게 알린다 - 없으면 알리지 않는다. */
    private val credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
) : PaymentProvider, PaymentLookupGateway, PaymentCheckoutGateway {
    override val providerId: String = properties.providerId
    override val supportedCurrencies: Set<String> = properties.supportedCurrencies
    override val supportedCountries: Set<String> = properties.supportedCountries

    /** 가맹점 코드가 정한다: 문서의 테스트 코드 → TEST, 그 밖 → LIVE ([KakaoPayMerchant]). */
    override val mode: PaymentMode = KakaoPayMerchant.modeOf(properties.cid)

    private val errorMapper = KakaoPayErrorMapper(providerId = providerId, credentialAlert = credentialAlert)

    /** 조회용: 조회의 오류는 확정으로 표시하지 않는다(`definitive` 는 늘 false). */
    private val lookupErrorMapper = KakaoPayErrorMapper(providerId = providerId, definitiveAllowed = false, credentialAlert = credentialAlert)

    /** 정기 결제 승인용: `-782`(정기 결제 실패)도 확정 거절이다. */
    private val subscriptionErrorMapper = KakaoPayErrorMapper(providerId = providerId, credentialAlert = credentialAlert, definitiveCodes = KakaoPayErrorMapper.SUBSCRIPTION_DEFINITIVE_CODES)

    /** 거래 번호로만 조회된다 - 주문 번호만 든 조회는 결제사를 모른다고 답한다([lookup]). */
    override val answersByOrderId: Boolean = false

    init {
        require(providerId.isNotBlank()) { "KakaoPay providerId must not be blank" }
        require(properties.secretKey.isNotBlank()) { "KakaoPay secretKey must not be blank" }
        require(properties.cid.isNotBlank()) { "KakaoPay cid must not be blank" }
    }

    /** 장부가 선점 전에 묻는다(주문 줄 락 안 - 문자열 검사만). 거래 번호 모양이 문서에서 확정되지 않았다([KakaoPayIdentifiers]). */
    override fun acceptsPaymentKey(providerPaymentId: String): Boolean = KakaoPayIdentifiers.isTid(providerPaymentId)

    /** 승인 토큰의 모양 - 장부가 선점 전에 묻는다(없으면 선점 없이 400). 선점 뒤에 거절하면 「모름」으로 읽혀 주문이 `CONFIRMING` 에 굳는다. */
    override fun acceptsApprovalToken(approvalToken: String?): Boolean = KakaoPayIdentifiers.isApprovalToken(approvalToken)

    // --- 결제창 ---

    /** 돌아올 주소(`return-url`)가 http(s) 절대 주소가 아니면 결제창을 열 수 없다 - 카카오페이는 `ready` 에 성공 · 취소 · 실패 주소를 받는다. */
    override fun canOpenCheckout(): Boolean = returnBase() != null

    /**
     * `ready` 를 서버에서 부른다 - 금액 · 상품명 · 주문 번호는 서버가 정한 값이다. 결제사가 답하지 못하면 [PaymentUnavailableException](503) - 주문은 `PENDING` 으로 남아 만료 작업이 닫고,
     * 같은 키로 다시 열면 그때 다시 부른다. 모든 `ready` 가 새 `tid` 를 만든다(이전 것은 결제되지 않은 채 카카오페이에 남는다 - 수용).
     */
    override fun checkout(order: CheckoutOrder): CheckoutInstruction {
        val base = returnBase()?.let { ReturnOrigins.resolve(it, order.returnOrigin, properties.allowedReturnOrigins) } ?: throw PaymentUnavailableException()
        // 카카오페이는 원화만 받는다 - 다른 통화의 금액을 `total_amount` 로 보내지 않는다(구매자가 고른 결제사일 때의 마지막 방어)
        if (order.amount.normalizedCurrency != KRW) throw PaymentUnavailableException()
        val orderId = KakaoPayIdentifiers.orderId(order.orderId)
        val ready = try {
            guarded("ready") { seen ->
                val response = requireNotNull(
                    httpClient.postResponse(
                        clientName = properties.clientName,
                        path = properties.readyPath,
                        body = readyBody(order, orderId, base),
                        responseType = KakaoPayReadyResponse::class.java,
                    ) {
                        common("payment.kakaopay.ready", errorMapper)
                        timeout(properties.readyTimeout)
                    }.block(),
                )
                seen.status = response.statusCode
                response.body.toReady()
            }
        } catch (e: Exception) {
            if (e is InterruptedException || generateSequence<Throwable>(e) { it.cause }.any { it is InterruptedException }) throw e
            log.warn("KakaoPay ready failed for order={} {}", orderId, PaymentFailures.describe(e))
            throw PaymentUnavailableException()
        }
        return CheckoutInstruction(
            provider = providerId,
            kind = CHECKOUT_KIND,
            testMode = mode == PaymentMode.TEST,
            orderId = order.orderId,
            orderName = order.orderName,
            amount = order.amount,
            clientKey = null, // 카카오페이는 브라우저에 줄 공개 키가 없다 - 결제창 주소가 그 몫이다
            attributes = listOfNotNull(
                "pcUrl" to ready.pcUrl,
                "mobileUrl" to ready.mobileUrl,
                ready.appUrl?.let { "appUrl" to it },
            ).toMap(),
            providerPaymentId = ready.tid,
        )
    }

    private fun readyBody(order: CheckoutOrder, orderId: String, base: String): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "cid" to properties.cid.trim(),
            "partner_order_id" to orderId,
            "partner_user_id" to KakaoPayIdentifiers.partnerUserId(order.accountId, orderId),
            "item_name" to order.orderName.take(ITEM_NAME_MAX),
            "quantity" to 1,
            "total_amount" to order.amount.amount,
            "tax_free_amount" to 0, // 부가세(vat_amount)는 보내지 않으면 카카오페이가 (총액 - 비과세)/11 로 계산한다
            "approval_url" to returnUrl(base, orderId, "success"),
            "cancel_url" to returnUrl(base, orderId, "cancel"),
            "fail_url" to returnUrl(base, orderId, "fail"),
        ).also { withCidSecret(it) }

    /** `return-url` 에 우리 조각을 붙인다 - 성공일 때 카카오페이가 `pg_token` 을 이어 붙인다. */
    private fun returnUrl(base: String, orderId: String, result: String): String {
        val separator = if ('?' in base) '&' else '?'
        return "$base${separator}kind=$CHECKOUT_KIND&provider=${properties.providerId.urlEncoded()}&order=${orderId.urlEncoded()}&result=$result"
    }

    private fun returnBase(): String? {
        val raw = properties.returnUrl.trim()
        val uri = runCatching { URI.create(raw) }.getOrNull() ?: return null
        val ok = (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null
        return raw.takeIf { ok }
    }

    // --- 승인 ---

    override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
        val tid = KakaoPayIdentifiers.tid(request.providerPaymentId)
        val orderId = KakaoPayIdentifiers.orderId(request.merchantReferenceId.orEmpty())
        val token = KakaoPayIdentifiers.approvalToken(request.approvalToken)
        return guarded("approve") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = properties.approvePath,
                    body = linkedMapOf<String, Any?>(
                        "cid" to properties.cid.trim(),
                        "tid" to tid,
                        "partner_order_id" to orderId,
                        "partner_user_id" to KakaoPayIdentifiers.partnerUserId(request.accountId, orderId),
                        "pg_token" to token,
                        // 승인 금액은 장부가 정한 주문 금액이다 - `ready` 때의 총액과 다르면 카카오페이가 거절한다
                        "total_amount" to request.amount.amount,
                    ).also { withCidSecret(it) },
                    responseType = KakaoPayApproveResponse::class.java,
                ) {
                    common("payment.kakaopay.approve", errorMapper)
                }.block(),
            )
            seen.status = response.statusCode
            val body = response.body
            // 물은 결제가 맞는지 확인한다 - 다른 결제의 답을 이 주문의 사실로 읽지 않는다
            if (body.tid != tid) throw UnreadableAnswer("tid")
            if (body.partnerOrderId != orderId) throw UnreadableAnswer("partner_order_id")
            val total = body.amount?.total.need("amount.total")
            PaymentOperationResult(
                provider = providerId,
                providerPaymentId = tid,
                status = PaymentOperationStatus.CONFIRMED,
                amount = PaymentAmount(total, KRW),
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = response.trace.traceId?.takeIf(SAFE_TRACE::matches),
                    providerOperationId = body.aid?.takeIf(SAFE_ID::matches),
                    rawStatus = "SUCCESS_PAYMENT",
                    metadata = mapOf("orderId" to orderId),
                ),
                attributes = mapOf("method" to METHOD, "easyPayBrand" to BRAND),
                paidAt = body.approvedAt.toInstantOrNull(),
            )
        }
    }

    // --- 취소 · 환불 ---

    override fun cancel(request: PaymentCancelRequest): PaymentOperationResult {
        val tid = KakaoPayIdentifiers.tid(request.providerPaymentId)
        // 금액을 안 주면 남은 취소 가능 금액 전부 - 조회로 알아낸다
        val amount = request.amount?.amount ?: cancelable(tid)
        return cancelOnce(tid, amount, "payment.kakaopay.cancel", PaymentOperationStatus.CANCELED)
    }

    /** 부분 환불도 같은 호출이다. 응답의 `status` 가 `PART_CANCEL_PAYMENT` 면 일부, `CANCEL_PAYMENT` 면 전부이고 남은 금액은 `cancel_available_amount.total` 이다. */
    override fun refund(request: PaymentRefundRequest): PaymentOperationResult =
        cancelOnce(KakaoPayIdentifiers.tid(request.providerPaymentId), request.amount.amount, "payment.kakaopay.refund", PaymentOperationStatus.REFUNDED, request.recurring)

    private fun cancelOnce(tid: String, amount: Long, tag: String, done: PaymentOperationStatus, recurring: Boolean = false): PaymentOperationResult =
        guarded("cancel") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = properties.cancelPath,
                    body = linkedMapOf<String, Any?>(
                        "cid" to cidFor(recurring),
                        "tid" to tid,
                        "cancel_amount" to amount,
                        "cancel_tax_free_amount" to 0,
                    ).also { if (!recurring) withCidSecret(it) },
                    responseType = KakaoPayCancelResponse::class.java,
                ) {
                    common(tag, errorMapper)
                }.block(),
            )
            seen.status = response.statusCode
            val body = response.body
            if (body.tid != tid) throw UnreadableAnswer("tid")
            val cancelled = body.approvedCancelAmount?.total
            // 취소로 읽으려면 이 호출이 취소를 만들었다는 근거가 응답에 있어야 한다 - 없으면 모름(조회가 가린다)
            val status = if (body.status in CANCELLED_STATUSES && cancelled != null && cancelled > 0) done else PaymentOperationStatus.UNKNOWN
            PaymentOperationResult(
                provider = providerId,
                providerPaymentId = tid,
                status = status,
                amount = PaymentAmount(cancelled?.takeIf { status == done } ?: amount, KRW),
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = response.trace.traceId?.takeIf(SAFE_TRACE::matches),
                    providerOperationId = body.aid?.takeIf { status == done }?.takeIf(SAFE_ID::matches),
                    rawStatus = body.status?.takeIf(SAFE_STATUS::matches),
                    metadata = listOfNotNull(body.partnerOrderId?.takeIf(SAFE_ID::matches)?.let { "orderId" to it }).toMap(),
                ),
                attributes = listOfNotNull(
                    "method" to METHOD,
                    ("partial" to "true").takeIf { body.status == "PART_CANCEL_PAYMENT" && status == done },
                ).toMap(),
                remaining = body.cancelAvailableAmount?.total?.takeIf { status == done }?.let { PaymentAmount(it, KRW) },
            )
        }

    private fun cancelable(tid: String): Long {
        val snapshot = requireNotNull(lookup(PaymentLookupQuery(tid, null))) { "KakaoPay does not know the payment" }
        return snapshot.remaining.amount
    }

    // --- 조회 ---

    /**
     * 주문 조회(`/online/v1/payment/order`)는 **거래 번호(tid)로만** 묻는다 - 주문 번호로 묻는 길이 없다. 거래 번호 없이 주문 번호만 오면 결제를 모른다(null): 주문이 `PENDING` 이면 구매자가 아직 승인하지
     * 않은 것이고, 카카오페이는 서버 승인 없이 돈을 움직이지 않으므로 아무 일도 없었다는 뜻이 맞다(만료 작업이 이것으로 `EXPIRED` 로 닫는다).
     */
    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? {
        val tid = query.providerPaymentId?.takeIf { it.isNotBlank() }?.let(KakaoPayIdentifiers::tid) ?: return null
        val orderId = query.orderId?.takeIf { it.isNotBlank() }?.let(KakaoPayIdentifiers::orderId)
        return guarded("lookup") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = properties.orderPath,
                    body = linkedMapOf<String, Any?>("cid" to cidFor(query.recurring), "tid" to tid).also { if (!query.recurring) withCidSecret(it) },
                    responseType = KakaoPayOrderResponse::class.java,
                ) {
                    // 조회의 오류는 돈에 대해 아무것도 말하지 않는다 - 확정으로 읽히지 않게 한다
                    common("payment.kakaopay.lookup", lookupErrorMapper)
                    timeout(properties.lookupTimeout)
                }.block(),
            )
            seen.status = response.statusCode
            val body = response.body
            if (body.tid != tid) throw UnreadableAnswer("tid")
            if (orderId != null && body.partnerOrderId != orderId) throw UnreadableAnswer("partner_order_id")
            body.toSnapshot(tid)
        }
    }

    /** 조회 응답 → 중립 스냅샷. 읽을 수 없거나 모르는 값이거나 서로 어긋나면 던진다(추측하지 않는다) - 호출한 쪽에서는 모름으로 읽힌다. */
    private fun KakaoPayOrderResponse.toSnapshot(tid: String): PaymentSnapshot {
        val snapshotStatus = status.toSnapshotStatus().need("status")
        val total = amount?.total.need("amount.total")
        val done = paymentActionDetails.orEmpty().filter { it.paymentActionType == "CANCEL" }
        // 상태와 취소 목록이 어긋나면 읽을 수 없는 응답이다
        val refunded = snapshotStatus == ProviderPaymentStatus.REFUNDED || snapshotStatus == ProviderPaymentStatus.PARTIALLY_REFUNDED
        if (refunded && done.isEmpty()) throw UnreadableAnswer("payment_action_details")
        if (snapshotStatus == ProviderPaymentStatus.PAID && done.isNotEmpty()) throw UnreadableAnswer("payment_action_details")
        val refunds = done.map { cancel ->
            ProviderRefund(
                providerRefundId = cancel.aid?.takeIf(SAFE_ID::matches).need("aid"),
                amount = PaymentAmount(cancel.amount.need("payment_action_details.amount"), KRW),
                at = cancel.approvedAt.toInstantOrNull().need("payment_action_details.approved_at"),
            )
        }
        return PaymentSnapshot(
            providerPaymentId = tid,
            orderId = partnerOrderId?.takeIf(SAFE_ID::matches),
            status = snapshotStatus,
            total = PaymentAmount(total, KRW),
            remaining = PaymentAmount(total - refunds.sumOf { it.amount.amount }, KRW),
            method = METHOD,
            paidAt = approvedAt.toInstantOrNull(),
            refunds = refunds,
            easyPayBrand = BRAND,
        )
    }

    private fun String?.toSnapshotStatus(): ProviderPaymentStatus? =
        when (this) {
            "READY", "SEND_TMS", "OPEN_PAYMENT", "SELECT_METHOD", "ARS_WAITING", "AUTH_PASSWORD" -> ProviderPaymentStatus.IN_PROGRESS
            "SUCCESS_PAYMENT" -> ProviderPaymentStatus.PAID
            "PART_CANCEL_PAYMENT" -> ProviderPaymentStatus.PARTIALLY_REFUNDED
            "CANCEL_PAYMENT" -> ProviderPaymentStatus.REFUNDED
            "FAIL_AUTH_PASSWORD", "QUIT_PAYMENT", "FAIL_PAYMENT" -> ProviderPaymentStatus.FAILED
            else -> null // ISSUED_SID(정기 결제의 SID 발급)는 이 모듈의 일이 아니다 - 모르는 값은 모름
        }

    // --- 공통 ---

    private fun ExternalHttpRequestSpec.common(loggingTag: String, mapper: KakaoPayErrorMapper) {
        baseUrl(properties.baseUrl)
        header("Authorization", "SECRET_KEY ${properties.secretKey.trim()}")
        loggingTag(loggingTag)
        errorMapper(mapper)
    }

    /** 정기 결제 주문은 정기 가맹점 코드로 묻고 취소한다(없으면 단건 코드). */
    private fun cidFor(recurring: Boolean): String = if (recurring) properties.subscriptionCid.trim().ifEmpty { properties.cid.trim() } else properties.cid.trim()

    private fun withCidSecret(body: MutableMap<String, Any?>) {
        properties.cidSecret.trim().takeIf { it.isNotEmpty() }?.let { body["cid_secret"] = it }
    }

    private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { LocalDateTime.parse(it).atZone(KST).toInstant() }.getOrNull() }

    private fun <T : Any> T?.need(field: String): T = this ?: throw UnreadableAnswer(field)

    private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

    /** [guarded] 의 블록이 응답을 받으면 그 HTTP 상태를 적어 둔다 - 읽을 수 없는 응답 오류가 실제 상태를 달고 나가게. */
    private class Seen(var status: Int = 200)

    /**
     * 호출과 응답 해석 전체를 감싼다. 결제사가 답한 오류(타임아웃 · 네트워크 · 오류 코드 - [ExternalHttpException])와 호출자 잘못(`ApplicationException`)은 그대로 두고,
     * 그 밖의 모든 실패(깨진 JSON · 빈 응답 · 모르는 상태 · 읽을 수 없는 값)는 **본문을 인용하지 않는** `UNREADABLE_RESPONSE` 로 바꾼다. 결과는 모름으로 읽힌다(`PaymentFailures.outcomeOf`).
     */
    private inline fun <T> guarded(operation: String, block: (Seen) -> T): T {
        val seen = Seen()
        return try {
            block(seen)
        } catch (e: ExternalHttpException) {
            throw e
        } catch (e: dev.sumin.skeleton.common.ApplicationException) {
            throw e
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is InterruptedException }) throw e
            val reason = (e as? UnreadableAnswer)?.reason ?: e.javaClass.simpleName
            log.warn("KakaoPay {} answer could not be read (HTTP status {}, {})", operation, seen.status, reason)
            throw unreadable(seen.status)
        }
    }

    // --- 정기 결제 (SID) - [KakaoPayBillingProvider] 가 능력으로 드러낸다 ---

    /** `ready`(정기 가맹점 코드) - 첫 주문 번호가 `partner_order_id` 라 미리 정해져 있다. 창 값은 `ready` 가 준 주소들, 번호(`reference`)는 `tid`. 돌아올 주소가 없거나 원화가 아니면 null. */
    internal fun prepareSubscription(request: BillingPrepareRequest): PreparedBilling? {
        val base = returnBase()?.let { ReturnOrigins.resolve(it, request.returnOrigin, properties.allowedReturnOrigins) } ?: return null
        if (request.amount.normalizedCurrency != KRW) return null
        val orderId = KakaoPayIdentifiers.orderId(request.firstOrderId)
        val ready = guarded("ready") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = properties.readyPath,
                    body = linkedMapOf<String, Any?>(
                        "cid" to cidFor(true),
                        "partner_order_id" to orderId,
                        "partner_user_id" to KakaoPayIdentifiers.partnerUserId(request.customerKey, orderId),
                        "item_name" to request.planName.take(ITEM_NAME_MAX),
                        "quantity" to 1,
                        "total_amount" to request.amount.amount,
                        "tax_free_amount" to 0,
                        "approval_url" to billingReturnUrl(base, request.subscriptionId, "success"),
                        "cancel_url" to billingReturnUrl(base, request.subscriptionId, "cancel"),
                        "fail_url" to billingReturnUrl(base, request.subscriptionId, "fail"),
                    ),
                    responseType = KakaoPayReadyResponse::class.java,
                ) {
                    common("payment.kakaopay.subscription-ready", errorMapper)
                    timeout(properties.readyTimeout)
                }.block(),
            )
            seen.status = response.statusCode
            response.body.toReady()
        }
        val setup = BillingSetup(
            provider = providerId,
            kind = BILLING_KIND,
            testMode = mode == PaymentMode.TEST,
            clientKey = null, // 공개 키가 없다 - 결제창 주소가 그 몫이다
            customerKey = request.customerKey,
            attributes = listOfNotNull("pcUrl" to ready.pcUrl, "mobileUrl" to ready.mobileUrl, ready.appUrl?.let { "appUrl" to it }).toMap(),
        )
        return PreparedBilling(setup, ready.tid)
    }

    /** 돌아온 창의 `pg_token` 과 예약 때의 `tid` 의 모양 - 장부가 자리를 선점하기 전에 묻는다. */
    internal fun acceptsSubscriptionRegistration(providerPayload: Map<String, Any?>, reference: String?): Boolean =
        reference != null && KakaoPayIdentifiers.isTid(reference) && KakaoPayIdentifiers.isApprovalToken(providerPayload["pg_token"] as? String)

    /** 1회차 결제 = 등록: `approve`(정기 가맹점 코드)의 응답이 SID 를 준다. SID 를 못 읽으면 [RegisteredCharge.issue] 가 null(돈은 움직였다). */
    internal fun registerSubscription(request: RegisterWithChargeRequest): RegisteredCharge {
        val tid = KakaoPayIdentifiers.tid(request.reference.orEmpty())
        val orderId = KakaoPayIdentifiers.orderId(request.orderId)
        val token = KakaoPayIdentifiers.approvalToken(request.providerPayload["pg_token"] as? String)
        return guarded("approve") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = properties.approvePath,
                    body = linkedMapOf<String, Any?>(
                        "cid" to cidFor(true),
                        "tid" to tid,
                        "partner_order_id" to orderId,
                        "partner_user_id" to KakaoPayIdentifiers.partnerUserId(request.customerKey, orderId),
                        "pg_token" to token,
                        "total_amount" to request.amount.amount,
                    ),
                    responseType = KakaoPayApproveResponse::class.java,
                ) {
                    common("payment.kakaopay.subscription-register", errorMapper)
                }.block(),
            )
            seen.status = response.statusCode
            val body = response.body
            if (body.tid != tid) throw UnreadableAnswer("tid")
            if (body.partnerOrderId != orderId) throw UnreadableAnswer("partner_order_id")
            val result = confirmedResult(body, tid, orderId, response.trace.traceId)
            val sid = body.sid?.takeIf(SAFE_SID::matches)
            RegisteredCharge(sid?.let { BillingIssue(it, PaymentCard(body.cardInfo?.issuerCode?.takeIf(SAFE_ISSUER::matches), null)) }, result)
        }
    }

    /** 2회차부터: `subscription`(SID). 응답의 `tid` 가 이 주문의 결제 키다. `-782` 는 확정 거절, 그 밖은 모름(조회가 불가하니 사람이 본다). */
    internal fun chargeSubscription(request: BillingChargeRequest): PaymentOperationResult {
        val orderId = KakaoPayIdentifiers.orderId(request.orderId)
        // 모양이 틀린 빌링키는 결제사를 부르기 전에 막는다 - 돈이 움직이지 않았으니 모름이 아니라 확정 거절이다
        val sid = request.token.takeIf(SAFE_SID::matches) ?: throw invalidBillingKey()
        return guarded("subscription") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = properties.subscriptionPath,
                    body = linkedMapOf<String, Any?>(
                        "cid" to cidFor(true),
                        "sid" to sid,
                        "partner_order_id" to orderId,
                        "partner_user_id" to KakaoPayIdentifiers.partnerUserId(request.customerKey, orderId),
                        "item_name" to request.orderName.take(ITEM_NAME_MAX),
                        "quantity" to 1,
                        "total_amount" to request.amount.amount,
                        "tax_free_amount" to 0,
                    ),
                    responseType = KakaoPayApproveResponse::class.java,
                ) {
                    common("payment.kakaopay.subscription-charge", subscriptionErrorMapper)
                }.block(),
            )
            seen.status = response.statusCode
            val body = response.body
            if (body.partnerOrderId != orderId) throw UnreadableAnswer("partner_order_id")
            val tid = body.tid?.takeIf(KakaoPayIdentifiers::isTid).need("tid")
            confirmedResult(body, tid, orderId, response.trace.traceId)
        }
    }

    /** SID 를 비활성한다. 실패하면 상태를 물어 이미 비활성이면 정상(멱등)으로 읽고, 아니면 처음 오류를 던진다. */
    internal fun revokeSubscription(token: String) {
        val sid = token.takeIf(SAFE_SID::matches) ?: throw KakaoPayIdentifierRejectedException()
        try {
            httpClient.postResponse(
                clientName = properties.clientName,
                path = properties.inactivePath,
                body = linkedMapOf<String, Any?>("cid" to cidFor(true), "sid" to sid),
                responseType = KakaoPaySidResponse::class.java,
            ) { common("payment.kakaopay.subscription-inactive", errorMapper) }.block()
        } catch (e: ExternalHttpException) {
            if (!sidInactive(sid)) throw e
        }
    }

    private fun sidInactive(sid: String): Boolean =
        try {
            val body = httpClient.postResponse(
                clientName = properties.clientName,
                path = properties.statusPath,
                body = linkedMapOf<String, Any?>("cid" to cidFor(true), "sid" to sid),
                responseType = KakaoPaySidResponse::class.java,
            ) { common("payment.kakaopay.subscription-status", lookupErrorMapper) }.block()?.body
            body != null && (body.status == "INACTIVE" || body.available == false)
        } catch (e: Exception) {
            false
        }

    private fun confirmedResult(body: KakaoPayApproveResponse, tid: String, orderId: String, traceId: String?): PaymentOperationResult =
        PaymentOperationResult(
            provider = providerId,
            providerPaymentId = tid,
            status = PaymentOperationStatus.CONFIRMED,
            amount = PaymentAmount(body.amount?.total.need("amount.total"), KRW),
            trace = ProviderTrace(
                provider = providerId,
                providerRequestId = traceId?.takeIf(SAFE_TRACE::matches),
                providerOperationId = body.aid?.takeIf(SAFE_ID::matches),
                rawStatus = "SUCCESS_PAYMENT",
                metadata = mapOf("orderId" to orderId),
            ),
            attributes = mapOf("method" to METHOD, "easyPayBrand" to BRAND),
            paidAt = body.approvedAt.toInstantOrNull(),
        )

    /** 정기 등록의 돌아올 주소 - 단건과 같은 `return-url` 에 `kind=KAKAOPAY_BILLING_REDIRECT&provider=<id>&sub=<구독 id>&result=…` 를 붙인다. */
    private fun billingReturnUrl(base: String, subscriptionId: String, result: String): String {
        val separator = if ('?' in base) '&' else '?'
        return "$base${separator}kind=$BILLING_KIND&provider=${properties.providerId.urlEncoded()}&sub=${subscriptionId.urlEncoded()}&result=$result"
    }

    private fun invalidBillingKey(): PaymentProviderException =
        PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = INVALID_BILLING_KEY,
                message = null,
                trace = ProviderTrace(provider = providerId, rawCode = INVALID_BILLING_KEY),
                upstreamStatus = 400,
                retryable = false,
                definitive = true,
                rawBody = null,
            ),
            clientName = properties.clientName,
            method = "HTTP",
            uri = URI.create(properties.baseUrl.ifBlank { DEFAULT_BASE_URL }),
        )

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

    /** 응답을 읽을 수 없다는 사유(칸 이름 같은 고정 문자열 - 값은 담지 않는다). 밖으로 나가지 않고 [guarded] 가 로그에 사유만 남긴다. */
    private class UnreadableAnswer(val reason: String) : RuntimeException(reason, null, false, false)

    private fun KakaoPayReadyResponse.toReady(): Ready {
        val tid = tid?.takeIf(KakaoPayIdentifiers::isTid).need("tid")
        return Ready(
            tid = tid,
            pcUrl = nextRedirectPcUrl.windowUrl().need("next_redirect_pc_url"),
            mobileUrl = nextRedirectMobileUrl.windowUrl().need("next_redirect_mobile_url"),
            appUrl = nextRedirectAppUrl.windowUrl(),
        )
    }

    /** 브라우저가 따라갈 주소 - http(s) 절대 주소만(`javascript:` 같은 것을 구매자 화면에 내려보내지 않는다). */
    private fun String?.windowUrl(): String? {
        val raw = this?.trim()?.takeIf { it.isNotEmpty() && it.length <= WINDOW_URL_MAX } ?: return null
        val uri = runCatching { URI.create(raw) }.getOrNull() ?: return null
        val web = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
        return raw.takeIf { web && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }

    private class Ready(val tid: String, val pcUrl: String, val mobileUrl: String, val appUrl: String?)

    private companion object {
        val log = LoggerFactory.getLogger(KakaoPayProvider::class.java)

        const val CHECKOUT_KIND = "KAKAOPAY_REDIRECT"
        const val BILLING_KIND = "KAKAOPAY_BILLING_REDIRECT"
        const val INVALID_BILLING_KEY = "INVALID_BILLING_KEY"
        const val METHOD = "EASY_PAY"

        /** 이 결제사가 곧 브랜드다(토스 경유와 달리) - 장부 `easy_pay_brand`. */
        const val BRAND = "KAKAOPAY"
        const val KRW = "KRW"
        const val UNREADABLE_RESPONSE = "UNREADABLE_RESPONSE"
        const val ITEM_NAME_MAX = 100
        const val WINDOW_URL_MAX = 2000
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val CANCELLED_STATUSES = setOf("PART_CANCEL_PAYMENT", "CANCEL_PAYMENT")

        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SAFE_SID = Regex("[A-Za-z0-9_-]{1,100}")
        val SAFE_ISSUER = Regex("[A-Za-z0-9_]{1,40}")
        val SAFE_TRACE = Regex("[A-Za-z0-9_.:-]{1,100}")
        val SAFE_STATUS = Regex("[A-Z_]{1,40}")
    }
}

/** 카카오페이가 `ready` 에 주는 값 중 읽는 칸만. */
data class KakaoPayReadyResponse(
    val tid: String? = null,
    @JsonProperty("next_redirect_app_url") val nextRedirectAppUrl: String? = null,
    @JsonProperty("next_redirect_mobile_url") val nextRedirectMobileUrl: String? = null,
    @JsonProperty("next_redirect_pc_url") val nextRedirectPcUrl: String? = null,
)

/** 금액 객체(`amount` · `approved_cancel_amount` · `canceled_amount` · `cancel_available_amount`)에서 읽는 한 칸. */
data class KakaoPayAmounts(val total: Long? = null)

/** `approve` 응답에서 읽는 칸. 카드 상세(`card_info`) · 상품 · 회원 id 는 선언하지 않았으니 버려진다. */
data class KakaoPayApproveResponse(
    val aid: String? = null,
    val tid: String? = null,
    /** 정기 결제 가맹점 코드로 1회차를 승인하면 오는 빌링키(SID) - 비밀이라 어디에도 찍지 않는다([toString] 은 가린다). */
    val sid: String? = null,
    @JsonProperty("card_info") val cardInfo: KakaoPayCardInfo? = null,
    @JsonProperty("partner_order_id") val partnerOrderId: String? = null,
    val amount: KakaoPayAmounts? = null,
    @JsonProperty("approved_at") val approvedAt: String? = null,
) {
    override fun toString(): String = "KakaoPayApproveResponse(tid=$tid, sid=${if (sid == null) "null" else "<redacted>"})"
}

/** 카드 정보 중 발급사 코드 한 칸만 읽는다(끝 네 글자 · bin 은 없다 - 읽지 않는다). */
data class KakaoPayCardInfo(@JsonProperty("kakaopay_issuer_corp_code") val issuerCode: String? = null)

/** `inactive` · `status` 응답에서 읽는 칸. */
data class KakaoPaySidResponse(val available: Boolean? = null, val status: String? = null)

/** `cancel` 응답에서 읽는 칸. */
data class KakaoPayCancelResponse(
    val aid: String? = null,
    val tid: String? = null,
    val status: String? = null,
    @JsonProperty("partner_order_id") val partnerOrderId: String? = null,
    @JsonProperty("approved_cancel_amount") val approvedCancelAmount: KakaoPayAmounts? = null,
    @JsonProperty("cancel_available_amount") val cancelAvailableAmount: KakaoPayAmounts? = null,
)

/** `order`(주문 조회) 응답에서 읽는 칸. */
data class KakaoPayOrderResponse(
    val tid: String? = null,
    val status: String? = null,
    @JsonProperty("partner_order_id") val partnerOrderId: String? = null,
    val amount: KakaoPayAmounts? = null,
    @JsonProperty("approved_at") val approvedAt: String? = null,
    @JsonProperty("payment_action_details") val paymentActionDetails: List<KakaoPayActionDetail>? = null,
)

data class KakaoPayActionDetail(
    val aid: String? = null,
    val amount: Long? = null,
    @JsonProperty("approved_at") val approvedAt: String? = null,
    @JsonProperty("payment_action_type") val paymentActionType: String? = null,
)
