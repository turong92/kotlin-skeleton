package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpException
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCancelRequest
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentFailures
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
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.ProviderTrace
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

/**
 * 네이버페이 단건 결제(결제형 REST, 결제 예약 → 결제창 → 서버 승인 → 취소 · 조회). 한 빈이 결제 예약 · 승인 · 환불 · 조회를 모두 맡는다(라우터에 결제사가 하나로 보이게).
 * 정기 결제(자동결제) · 웹훅은 이 모듈에 없다 - 이번에 읽은 문서에서 결제 이벤트 웹훅을 찾지 못했다(`docs/modules/payment-naverpay.md`). 결과는 승인 응답과 결제 내역 조회로 맞춘다.
 *
 * **결제창 흐름**: [checkout] 이 결제를 서버에서 예약하고(`v2/reserve` - 금액 · 주문 번호는 장부의 값) 받은 예약 번호로 결제창 주소(`<결제창 도메인>/payments/{예약 번호}`)를 만들어 프론트에 준다. 네이버 SDK 는 쓰지 않는다 - 구매자 브라우저는 그 주소로 이동하기만 한다.
 * 구매자가 결제하면 네이버가 우리 `returnUrl` 로 `paymentId` 를 붙여 돌려보내고, 프론트가 그 번호로 승인을 부른다([confirm] = `apply/payment`). **서버 승인 없이는 돈이 움직이지 않는다**(승인은 결제창이 열린 지 10분 안에).
 *
 * **금액은 서버가 고정한다**: SDK 로 브라우저가 결제창을 열면 금액을 구매자가 바꿀 수 있고 승인 API 는 금액을 받지 않아 이미 빠진 돈을 되돌려야 한다 - 그래서 문서의 「SDK 미사용」 길(서버 예약)을 쓴다. 그래도 승인 응답의 총액 · 주문 번호(`merchantPayKey`)는
 * **있는 그대로 돌려주고 장부가 주문과 대조한다**(다른 주문의 결제 번호를 들고 오는 경우 등) - 다르면 장부가 주문을 `PAID` 로 두지 않고 사람이 보게 표시한다(`AMOUNT_MISMATCH` · `ORDER_MISMATCH`). 이 모듈이 자동으로 취소하지는 않는다.
 *
 * **응답 본문은 어디에도 싣지 않는다**: 응답은 아래 data class 가 선언한 칸만 읽고(나머지 - 카드 정보 · 상품명 · 사용자 키 - 는 버려진다), 오류는 코드만 예외에 담으며([NaverPayErrorMapper]),
 * 읽지 못한 응답은 본문을 인용하지 않는 [PaymentProviderException] 으로 바꾼다.
 */
class NaverPayProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: NaverPayProperties,
    /** 인증 계열 거절(키 폐기 · 오타)을 주인에게 알린다 - 없으면 알리지 않는다. */
    private val credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
) : PaymentProvider, PaymentLookupGateway, PaymentCheckoutGateway {
    override val providerId: String = properties.providerId
    override val supportedCurrencies: Set<String> = properties.supportedCurrencies
    override val supportedCountries: Set<String> = properties.supportedCountries

    /** 모드 설정이 정한다: 개발 → TEST, 운영 → LIVE. */
    override val mode: PaymentMode = properties.paymentMode

    private val errorMapper = NaverPayErrorMapper(providerId = providerId, credentialAlert = credentialAlert)

    /** 조회용: 조회의 오류는 확정으로 표시하지 않는다(`definitive` 는 늘 false). */
    private val lookupErrorMapper = NaverPayErrorMapper(providerId = providerId, definitiveAllowed = false, credentialAlert = credentialAlert)

    init {
        require(providerId.isNotBlank()) { "NaverPay providerId must not be blank" }
        require(properties.clientId.isNotBlank()) { "NaverPay clientId must not be blank" }
        require(properties.clientSecret.isNotBlank()) { "NaverPay clientSecret must not be blank" }
        require(properties.chainId.isNotBlank()) { "NaverPay chainId must not be blank" }
    }

    /** 장부가 선점 전에 묻는다(주문 줄 락 안 - 문자열 검사만). 결제 번호 모양이 문서에서 확정되지 않았다([NaverPayIdentifiers]). */
    override fun acceptsPaymentKey(providerPaymentId: String): Boolean = NaverPayIdentifiers.isPaymentId(providerPaymentId)

    override fun confirmDeclineCategory(providerCode: String?): ConfirmDeclineCategory = NaverPayErrorMapper.declineCategory(providerCode)

    // --- 결제창 ---

    /** 돌아올 주소(`return-url`)가 http(s) 절대 주소가 아니면 결제창을 예약할 수 없다 - 네이버페이는 결제 예약 때 `returnUrl` 을 받는다. */
    override fun canOpenCheckout(): Boolean = returnBase() != null

    /**
     * 결제를 **서버에서 예약**한다(`v2/reserve`) - 금액 · 주문 번호 · 상품명 · 과세 구분 · 돌아올 주소는 서버가 정한 값이고 브라우저는 예약 번호가 든 결제창 주소만 받는다(구매자가 금액을 바꿀 길이 없다).
     * 결제사가 답하지 못하면 [PaymentUnavailableException](503) - 주문은 `PENDING` 으로 남아 만료 작업이 닫고, 같은 키로 다시 열면 그때 다시 예약한다. 모든 예약이 새 결제창이다(이전 것은 결제되지 않은 채 30분 뒤 만료된다 - 수용).
     * 결제 번호는 아직 없다 - 구매자가 결제를 마치면 돌아온 주소에 실려 온다.
     */
    override fun checkout(order: CheckoutOrder): CheckoutInstruction {
        val base = returnBase() ?: throw PaymentUnavailableException()
        // 네이버페이는 원화만 받는다 - 다른 통화의 금액을 예약하지 않는다(구매자가 고른 결제사일 때의 마지막 방어)
        if (order.amount.normalizedCurrency != KRW) throw PaymentUnavailableException()
        val orderId = NaverPayIdentifiers.orderId(order.orderId)
        val reserveId = try {
            guarded("reserve") { seen ->
                val response = requireNotNull(
                    httpClient.postResponse(
                        clientName = properties.clientName,
                        path = properties.reservePath,
                        body = reserveBody(order, orderId, base),
                        responseType = NaverPayReserveResponse::class.java,
                    ) {
                        common("payment.naverpay.reserve", errorMapper)
                        header(IDEMPOTENCY_HEADER, NaverPayIdentifiers.reserveKey())
                        timeout(properties.reserveTimeout)
                    }.block(),
                )
                seen.status = response.statusCode
                requireSuccess(response.body.code, errorMapper, setOf(SUCCESS))
                response.body.body?.reserveId?.takeIf(NaverPayIdentifiers::isReserveId).need("reserveId")
            }
        } catch (e: Exception) {
            if (e is InterruptedException || generateSequence<Throwable>(e) { it.cause }.any { it is InterruptedException }) throw e
            log.warn("NaverPay reserve failed for order={} {}", orderId, PaymentFailures.describe(e))
            throw PaymentUnavailableException()
        }
        return CheckoutInstruction(
            provider = providerId,
            kind = CHECKOUT_KIND,
            testMode = mode == PaymentMode.TEST,
            orderId = order.orderId,
            orderName = order.orderName,
            amount = order.amount,
            clientKey = null, // 브라우저에 줄 공개 키가 없다 - 결제창 주소가 그 몫이다
            attributes = mapOf("windowUrl" to "${properties.windowBase}/payments/$reserveId"),
        )
    }

    private fun reserveBody(order: CheckoutOrder, orderId: String, base: String): Map<String, Any?> {
        val name = order.orderName.take(NAME_MAX)
        return linkedMapOf(
            "modelVersion" to "2",
            "merchantPayKey" to orderId,
            "productName" to name,
            "productCount" to 1,
            "totalPayAmount" to order.amount.amount,
            "taxScopeAmount" to order.amount.amount, // 모두 과세 - 면세 상품이 있는 가맹점은 계약에 맞춰 이 구분을 고친다
            "taxExScopeAmount" to 0,
            "returnUrl" to returnUrl(base, orderId),
            // 상품 분류는 문서 예시의 값(ETC)을 못 잰 가정으로 쓴다 - 구매자 정보(merchantUserKey · purchaser*)는 보내지 않는다
            "productItems" to listOf(linkedMapOf("categoryType" to "ETC", "categoryId" to "ETC", "uid" to orderId, "name" to name, "count" to 1)),
        )
    }

    /** `return-url` 에 우리 조각을 붙인다 - 결제를 마치면 네이버페이가 `resultCode` · `paymentId` 를 이어 붙인다. */
    private fun returnUrl(base: String, orderId: String): String {
        val separator = if ('?' in base) '&' else '?'
        return "$base${separator}kind=$CHECKOUT_KIND&provider=${providerId.urlEncoded()}&order=${orderId.urlEncoded()}"
    }

    private fun returnBase(): String? {
        val raw = properties.returnUrl.trim()
        val uri = runCatching { URI.create(raw) }.getOrNull() ?: return null
        val ok = (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawFragment == null
        return raw.takeIf { ok }
    }

    // --- 승인 ---

    override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
        val paymentId = NaverPayIdentifiers.paymentId(request.providerPaymentId)
        NaverPayIdentifiers.orderId(request.merchantReferenceId.orEmpty())
        return guarded("apply") { seen ->
            val response = requireNotNull(
                httpClient.postFormResponse(
                    clientName = properties.clientName,
                    path = properties.applyPath,
                    form = mapOf("paymentId" to paymentId),
                    responseType = NaverPayApplyResponse::class.java,
                ) {
                    common("payment.naverpay.apply", errorMapper)
                    header(IDEMPOTENCY_HEADER, NaverPayIdentifiers.applyKey(paymentId))
                    timeout(properties.applyTimeout)
                }.block(),
            )
            seen.status = response.statusCode
            val answer = response.body
            requireSuccess(answer.code, errorMapper, setOf(SUCCESS))
            val detail = answer.body?.detail.need("detail")
            // 물은 결제가 맞는지 확인한다 - 다른 결제의 답을 이 주문의 사실로 읽지 않는다
            if (answer.body?.paymentId != paymentId || detail.paymentId != paymentId) throw UnreadableAnswer("paymentId")
            if (detail.admissionTypeCode != "01" || detail.admissionState != "SUCCESS") throw UnreadableAnswer("admissionState")
            // 총액과 주문 번호는 읽은 그대로 돌려준다 - 주문과 맞는지는 장부가 대조한다(결제창 값은 브라우저가 쥐었다)
            val total = detail.totalPayAmount.need("totalPayAmount")
            val merchantPayKey = detail.merchantPayKey?.takeIf(SAFE_ID::matches).need("merchantPayKey")
            PaymentOperationResult(
                provider = providerId,
                providerPaymentId = paymentId,
                status = PaymentOperationStatus.CONFIRMED,
                amount = PaymentAmount(total, KRW),
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = response.trace.traceId?.takeIf(SAFE_TRACE::matches),
                    providerOperationId = detail.payHistId?.takeIf(SAFE_ID::matches),
                    rawStatus = "SUCCESS",
                    metadata = mapOf("orderId" to merchantPayKey),
                ),
                attributes = mapOf("method" to METHOD),
                paidAt = detail.admissionYmdt.toInstantOrNull(),
            )
        }
    }

    // --- 취소 · 환불 ---

    override fun cancel(request: PaymentCancelRequest): PaymentOperationResult {
        val paymentId = NaverPayIdentifiers.paymentId(request.providerPaymentId)
        // 금액을 안 주면 남은 취소 가능 금액 전부 - 조회로 알아낸다
        val amount = request.amount?.amount ?: cancelable(paymentId)
        return cancelOnce(paymentId, amount, request.reason, request.idempotencyKey?.value, "payment.naverpay.cancel", PaymentOperationStatus.CANCELED)
    }

    /** 부분 환불도 같은 호출이다. 응답의 `totalRestAmount` 가 남은 금액이다. */
    override fun refund(request: PaymentRefundRequest): PaymentOperationResult =
        cancelOnce(NaverPayIdentifiers.paymentId(request.providerPaymentId), request.amount.amount, request.reason, request.idempotencyKey?.value, "payment.naverpay.refund", PaymentOperationStatus.REFUNDED)

    private fun cancelOnce(paymentId: String, amount: Long, reason: String?, idempotencyKey: String?, tag: String, done: PaymentOperationStatus): PaymentOperationResult =
        guarded("cancel") { seen ->
            val response = requireNotNull(
                httpClient.postFormResponse(
                    clientName = properties.clientName,
                    path = properties.cancelPath,
                    form = linkedMapOf(
                        "paymentId" to paymentId,
                        "cancelAmount" to amount.toString(),
                        "cancelReason" to (reason?.take(REASON_MAX)?.takeIf { it.isNotBlank() } ?: DEFAULT_REASON),
                        "cancelRequester" to CANCEL_REQUESTER_MERCHANT, // 문서: 구분이 애매하면 가맹점 관리자
                        // 모두 과세로 결제했다(checkout) - 과세 + 면세 = 취소 금액이어야 한다
                        "taxScopeAmount" to amount.toString(),
                        "taxExScopeAmount" to "0",
                    ),
                    responseType = NaverPayCancelResponse::class.java,
                ) {
                    common(tag, errorMapper)
                    header(IDEMPOTENCY_HEADER, NaverPayIdentifiers.cancelKey(idempotencyKey))
                    timeout(properties.applyTimeout)
                }.block(),
            )
            seen.status = response.statusCode
            val answer = response.body
            // CancelNotComplete: 문서는 「재시도 없이 취소 성공으로 두라 - 네이버페이가 자동으로 다시 처리한다」고 한다
            requireSuccess(answer.code, errorMapper, setOf(SUCCESS, CANCEL_NOT_COMPLETE))
            val body = answer.body.need("body")
            if (body.paymentId != paymentId) throw UnreadableAnswer("paymentId")
            val cancelled = body.primaryPayCancelAmount.need("primaryPayCancelAmount") + (body.npointCancelAmount ?: 0) + (body.giftCardCancelAmount ?: 0) + (body.discountCancelAmount ?: 0)
            val rest = body.totalRestAmount.need("totalRestAmount")
            // 취소로 읽으려면 이 호출이 취소를 만들었다는 근거가 응답에 있어야 한다 - 없으면 모름(조회가 가린다)
            val status = if (cancelled > 0) done else PaymentOperationStatus.UNKNOWN
            PaymentOperationResult(
                provider = providerId,
                providerPaymentId = paymentId,
                status = status,
                amount = PaymentAmount(cancelled.takeIf { status == done } ?: amount, KRW),
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = response.trace.traceId?.takeIf(SAFE_TRACE::matches),
                    providerOperationId = body.payHistId?.takeIf { status == done }?.takeIf(SAFE_ID::matches),
                    rawStatus = answer.code?.takeIf(SAFE_STATUS::matches),
                ),
                attributes = listOfNotNull(
                    "method" to METHOD,
                    ("partial" to "true").takeIf { rest > 0 && status == done },
                ).toMap(),
                remaining = PaymentAmount(rest, KRW).takeIf { status == done },
            )
        }

    private fun cancelable(paymentId: String): Long {
        val snapshot = requireNotNull(lookup(PaymentLookupQuery(paymentId, null))) { "NaverPay does not know the payment" }
        return snapshot.remaining.amount
    }

    // --- 조회 ---

    /**
     * 결제 내역 조회(`list/history/{paymentId}`)는 **결제 번호로만** 묻는다 - 주문 번호로 묻는 길이 없다(기간 조회뿐). 결제 번호 없이 주문 번호만 오면 결제를 모른다(null): 주문이 `PENDING` 이면
     * 구매자가 승인 단계까지 오지 않은 것이고, 네이버페이는 서버 승인 없이 돈을 움직이지 않으므로 아무 일도 없었다는 뜻이 맞다(만료 작업이 이것으로 `EXPIRED` 로 닫는다).
     * 승인 전의 결제 번호를 물으면 내역이 비어 있다 - 그것도 null.
     */
    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? {
        val paymentId = query.providerPaymentId?.takeIf { it.isNotBlank() }?.let(NaverPayIdentifiers::paymentId) ?: return null
        val orderId = query.orderId?.takeIf { it.isNotBlank() }?.let(NaverPayIdentifiers::orderId)
        return guarded("lookup") { seen ->
            val response = requireNotNull(
                httpClient.postResponse(
                    clientName = properties.clientName,
                    path = "${properties.historyPath}/$paymentId",
                    body = mapOf("pageNumber" to 1, "rowsPerPage" to HISTORY_ROWS),
                    responseType = NaverPayHistoryResponse::class.java,
                ) {
                    // 조회의 오류는 돈에 대해 아무것도 말하지 않는다 - 확정으로 읽히지 않게 한다
                    common("payment.naverpay.lookup", lookupErrorMapper)
                    timeout(properties.lookupTimeout)
                }.block(),
            )
            seen.status = response.statusCode
            requireSuccess(response.body.code, lookupErrorMapper, setOf(SUCCESS))
            val body = response.body.body.need("body")
            val entries = body.list.orEmpty()
            if ((body.totalPageCount ?: 1) > 1) throw UnreadableAnswer("totalPageCount")
            if (entries.isEmpty()) return@guarded null
            if (entries.any { it.paymentId != paymentId }) throw UnreadableAnswer("paymentId")
            toSnapshot(paymentId, orderId, entries)
        }
    }

    /** 내역 → 중립 스냅샷. 읽을 수 없거나 모르는 값이거나 서로 어긋나면 던진다(추측하지 않는다) - 호출한 쪽에서는 모름으로 읽힌다. */
    private fun toSnapshot(paymentId: String, orderId: String?, entries: List<NaverPayHistoryEntry>): PaymentSnapshot {
        val originals = entries.filter { it.admissionTypeCode == "01" }
        val original = originals.firstOrNull { it.admissionState == "SUCCESS" } ?: originals.firstOrNull().need("admissionTypeCode")
        val merchantPayKey = original.merchantPayKey?.takeIf(SAFE_ID::matches).need("merchantPayKey")
        if (orderId != null && merchantPayKey != orderId) throw UnreadableAnswer("merchantPayKey")
        val total = original.totalPayAmount.need("totalPayAmount")
        if (original.admissionState != "SUCCESS") {
            // 승인 시도가 실패로 남았다 - 취소 이력이 있을 수 없다
            if (original.admissionState != "FAIL" || entries.size != originals.size) throw UnreadableAnswer("admissionState")
            return PaymentSnapshot(paymentId, merchantPayKey, ProviderPaymentStatus.FAILED, PaymentAmount(total, KRW), PaymentAmount(total, KRW), METHOD, null, emptyList())
        }
        val refunds = entries.filter { it.admissionTypeCode in CANCEL_TYPES && it.admissionState == "SUCCESS" }.map { cancel ->
            ProviderRefund(
                providerRefundId = cancel.payHistId?.takeIf(SAFE_ID::matches).need("payHistId"),
                amount = PaymentAmount(cancel.totalPayAmount.need("totalPayAmount"), KRW),
                at = cancel.admissionYmdt.toInstantOrNull().need("admissionYmdt"),
            )
        }
        val refunded = refunds.sumOf { it.amount.amount }
        // 취소한 합이 결제액을 넘으면 읽을 수 없는 응답이다
        if (refunded > total) throw UnreadableAnswer("cancel total")
        val status = when {
            refunded == 0L -> ProviderPaymentStatus.PAID
            refunded == total -> ProviderPaymentStatus.REFUNDED
            else -> ProviderPaymentStatus.PARTIALLY_REFUNDED
        }
        return PaymentSnapshot(
            providerPaymentId = paymentId,
            orderId = merchantPayKey,
            status = status,
            total = PaymentAmount(total, KRW),
            remaining = PaymentAmount(total - refunded, KRW),
            method = METHOD,
            paidAt = original.admissionYmdt.toInstantOrNull(),
            refunds = refunds,
        )
    }

    // --- 공통 ---

    private fun ExternalHttpRequestSpec.common(loggingTag: String, mapper: NaverPayErrorMapper) {
        baseUrl(properties.apiBaseUrl)
        header("X-Naver-Client-Id", properties.clientId.trim())
        header("X-Naver-Client-Secret", properties.clientSecret.trim())
        header("X-NaverPay-Chain-Id", properties.chainId.trim())
        loggingTag(loggingTag)
        errorMapper(mapper)
    }

    /** HTTP 200 안의 결과 코드가 성공이 아니면 오류로 올린다 - 코드가 없으면 읽을 수 없는 응답이다. */
    private fun requireSuccess(code: String?, mapper: NaverPayErrorMapper, ok: Set<String>) {
        val value = code ?: throw UnreadableAnswer("code")
        if (value in ok) return
        throw mapper.exceptionFor(value, upstreamStatus = 200, traceId = null, clientName = properties.clientName, method = "POST", uri = apiUri())
    }

    private fun apiUri(): URI = runCatching { URI.create(properties.apiBaseUrl) }.getOrDefault(URI.create("about:blank"))

    private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { LocalDateTime.parse(it, YMDT).atZone(KST).toInstant() }.getOrNull() }

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
            log.warn("NaverPay {} answer could not be read (HTTP status {}, {})", operation, seen.status, reason)
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
            uri = apiUri(),
        )

    /** 응답을 읽을 수 없다는 사유(칸 이름 같은 고정 문자열 - 값은 담지 않는다). 밖으로 나가지 않고 [guarded] 가 로그에 사유만 남긴다. */
    private class UnreadableAnswer(val reason: String) : RuntimeException(reason, null, false, false)

    private companion object {
        val log = LoggerFactory.getLogger(NaverPayProvider::class.java)

        const val CHECKOUT_KIND = "NAVERPAY_REDIRECT"
        const val NAME_MAX = 128
        const val METHOD = "EASY_PAY"
        const val KRW = "KRW"
        const val UNREADABLE_RESPONSE = "UNREADABLE_RESPONSE"
        const val SUCCESS = "Success"
        const val CANCEL_NOT_COMPLETE = "CancelNotComplete"
        const val IDEMPOTENCY_HEADER = "X-NaverPay-Idempotency-Key"
        const val CANCEL_REQUESTER_MERCHANT = "2"
        const val DEFAULT_REASON = "refund"
        const val REASON_MAX = 80
        const val HISTORY_ROWS = 100
        val CANCEL_TYPES = setOf("03", "04")
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        val YMDT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SAFE_TRACE = Regex("[A-Za-z0-9_.:-]{1,100}")
        val SAFE_STATUS = Regex("[A-Za-z_]{1,40}")
    }
}

/** 결제 예약(`v2/reserve`)에서 읽는 칸. */
data class NaverPayReserveResponse(val code: String? = null, val body: NaverPayReserveBody? = null)

data class NaverPayReserveBody(val reserveId: String? = null)

/** 결제 승인(`apply/payment`)에서 읽는 칸. 카드 정보 · 상품명 · 사용자 키 · 정산 칸은 선언하지 않았으니 버려진다. */
data class NaverPayApplyResponse(val code: String? = null, val body: NaverPayApplyBody? = null)

data class NaverPayApplyBody(val paymentId: String? = null, val detail: NaverPayApplyDetail? = null)

data class NaverPayApplyDetail(
    val paymentId: String? = null,
    val payHistId: String? = null,
    val merchantPayKey: String? = null,
    val admissionTypeCode: String? = null,
    val admissionYmdt: String? = null,
    val admissionState: String? = null,
    val totalPayAmount: Long? = null,
)

/** 취소(`cancel`)에서 읽는 칸. */
data class NaverPayCancelResponse(val code: String? = null, val body: NaverPayCancelBody? = null)

data class NaverPayCancelBody(
    val paymentId: String? = null,
    val payHistId: String? = null,
    val primaryPayCancelAmount: Long? = null,
    val npointCancelAmount: Long? = null,
    val giftCardCancelAmount: Long? = null,
    val discountCancelAmount: Long? = null,
    val totalRestAmount: Long? = null,
)

/** 결제 내역 조회(`list/history/{paymentId}`)에서 읽는 칸. */
data class NaverPayHistoryResponse(val code: String? = null, val body: NaverPayHistoryBody? = null)

data class NaverPayHistoryBody(val list: List<NaverPayHistoryEntry>? = null, val totalPageCount: Int? = null)

data class NaverPayHistoryEntry(
    val paymentId: String? = null,
    val payHistId: String? = null,
    val admissionState: String? = null,
    val admissionTypeCode: String? = null,
    val admissionYmdt: String? = null,
    val totalPayAmount: Long? = null,
    val merchantPayKey: String? = null,
)
