package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.http.ExternalHttpTimeoutException
import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.BillingPrepareRequest
import dev.sumin.skeleton.payment.PreparedBilling
import dev.sumin.skeleton.payment.RegisterWithChargeRequest
import dev.sumin.skeleton.payment.RegisteredCharge
import dev.sumin.skeleton.payment.BillingIssue
import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentBillingGateway
import dev.sumin.skeleton.payment.PaymentCard
import dev.sumin.skeleton.payment.PaymentEasyPayGateway
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentConfirmGateway
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.PaymentRefundGateway
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentSnapshot
import dev.sumin.skeleton.payment.PaymentWebhookGateway
import dev.sumin.skeleton.payment.PaymentWebhookRejectedException
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.ProviderTrace
import dev.sumin.skeleton.payment.RefundDeclineCategory
import dev.sumin.skeleton.payment.WebhookDelivery
import dev.sumin.skeleton.payment.WebhookEnvelope
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 시험용 메모리 결제사(스펙 §13.1) — 승인 · 조회 · 결제창 능력을 한 빈에 모았다. **호출마다 대본**을 준다([script]), 대본이 없으면 승인된다.
 * 결제사 쪽의 사실(결제 목록)을 따로 들고 있어서 "응답은 못 받았는데 결제사는 승인했다"를 그대로 만든다: 타임아웃 대본은 예외를 던지되
 * [ConfirmScript.TimeoutApplied] 면 결제사 쪽 상태는 `PAID` 로 바뀌어 있다. 이어서 [lookup] 이 그 사실을 말한다.
 *
 * 환불([refund])도 같은 방식이다: [RefundScript] 대본이 "환불됨 / 거절 / 타임아웃인데 적용됨 / 타임아웃이고 적용 안 됨 / 한참 뒤에 적용됨 / 금액이 다른 응답 / 거래 키 없는 응답"을 만들고,
 * 결제사 쪽 취소 목록([ProviderPayment.refunds])이 사실이다 — 조회가 그것을 `refunds` · `remaining` 으로 말한다. 결제사 관리 화면의 취소는 [consoleCancel].
 * 같은 멱등 키의 환불은 결제사가 한 번으로 본다(토스처럼) — 그래도 장부는 다시 보내지 않아야 하므로 [refundCalls] 가 호출 횟수를 잰다.
 *
 * 호출은 모두 기록한다([confirmCalls] · [lookupCalls] · [checkoutCalls] · [refundCalls] — 호출 횟수 · 멱등 키 · 금액).
 *
 * 웹훅 해석([parse])은 `key=value` 줄로 된 아주 작은 본문을 읽는다([webhookDelivery] 가 만든다): 결제 키 · 주문 번호만 꺼내고 본문의 `status` 는 읽지 않는다(토스 해석기처럼).
 * 기본은 서명 없는 결제사([webhookSigned] = false)이고, true 로 바꾸면 서명 헤더가 맞아야 한다.
 */
class ScriptedGateway(
    override val providerId: String = "scripted",
    override var mode: PaymentMode = PaymentMode.TEST,
    override val supportedCurrencies: Set<String> = setOf("KRW"),
    private val now: () -> Instant = { PaymentTestOrders.T0 },
    /**
     * 지금 이 스레드에 열린 장부 트랜잭션이 있는가(`PaymentLedgerStore.hasOpenTransaction`). 있으면 승인 · 조회 · 결제창 호출이 **던진다**
     * (스펙 §6.2 "외부 호출은 락 · 트랜잭션 밖"). 메모리 저장소는 같은 스레드의 트랜잭션에 합류해서 이 감시가 없으면 위반이 가려진다.
     * [PaymentLedgerKit] 가 자기 저장소를 연결한다. 기본은 감시 없음.
     */
    private val openTransaction: () -> Boolean = { false },
) : PaymentConfirmGateway, PaymentLookupGateway, PaymentCheckoutGateway, PaymentRefundGateway, PaymentWebhookGateway, PaymentBillingGateway, PaymentEasyPayGateway {
    /** 결제창 안 간편결제 목록 - 시험이 바꾼다. 기본 빈 목록 */
    @Volatile var easyPays: List<String> = emptyList()

    override fun easyPays(): List<String> = easyPays

    /** 이 결제사가 받는 결제 키의 모양 — 기본은 전부 받는다. 시험이 바꾼다. */
    @Volatile
    var acceptsPaymentKey: (String) -> Boolean = { true }

    override fun acceptsPaymentKey(providerPaymentId: String): Boolean = acceptsPaymentKey.invoke(providerPaymentId)

    /** 이 결제사가 받는 승인 토큰의 모양 - 기본은 전부 받는다. 시험이 바꾼다. */
    @Volatile
    var acceptsApprovalToken: (String?) -> Boolean = { true }

    override fun acceptsApprovalToken(approvalToken: String?): Boolean = acceptsApprovalToken.invoke(approvalToken)

    sealed interface ConfirmScript {
        /** 승인됨 — 결제사 쪽 결제를 `PAID` 로 두고 그 총액으로 답한다. */
        data object Approve : ConfirmScript

        /** 금액이 다른 승인 — 결제사 쪽 총액이 [total] 이다(요청의 금액이 아니다). */
        data class ApproveWithTotal(val total: PaymentAmount) : ConfirmScript

        /** 거절(확정) — 돈이 움직이지 않았다고 결제사가 말했다. */
        data class Decline(val code: String = "REJECT_CARD_COMPANY") : ConfirmScript

        /** 응답 제한 시간 초과인데 결제사는 **승인했다** — [total] 이 null 이면 요청한 금액으로, 아니면 그 총액으로. */
        data class TimeoutApplied(val total: PaymentAmount? = null) : ConfirmScript

        /** 응답 제한 시간 초과이고 결제사는 **아무것도 하지 않았다**(결제는 진행 중으로 남는다). */
        data object TimeoutNotApplied : ConfirmScript

        /** 응답 제한 시간 초과이고, 결제사가 **한참 뒤에** 적용한다 — [settle] 을 부를 때까지 진행 중이다. (대본으로는 [TimeoutNotApplied] 와 같다.) */
        data object TimeoutAppliedLater : ConfirmScript

        /** 결제사 5xx(모름) — 아무것도 적용되지 않았다. */
        data object ServerError : ConfirmScript
    }

    sealed interface RefundScript {
        /** 환불됨 — 결제사 쪽에 취소가 생기고 그 취소의 거래 키 · 남은 금액으로 답한다. */
        data object Approve : RefundScript

        /** 거절(확정) — 돈이 움직이지 않았다고 결제사가 말했다. */
        data class Decline(val code: String = "NOT_ALLOWED_PARTIAL_REFUND") : RefundScript

        /** 응답 제한 시간 초과인데 결제사는 **환불했다**. */
        data object TimeoutApplied : RefundScript

        /** 응답 제한 시간 초과이고 결제사는 **아무것도 하지 않았다**. */
        data object TimeoutNotApplied : RefundScript

        /** 응답 제한 시간 초과이고, 결제사가 **한참 뒤에** 적용한다 — [applyLate] 를 부를 때까지 아무 일도 없다. */
        data object TimeoutAppliedLater : RefundScript

        /** 결제사 5xx(모름) — 아무것도 적용되지 않았다. */
        data object ServerError : RefundScript

        /** 결제사가 [amount] 를 취소하고 그 금액으로 "환불됨"이라 답한다(요청한 금액이 아니다). */
        data class ApproveOtherAmount(val amount: PaymentAmount) : RefundScript

        /** 취소했고 "환불됨"이라 답하지만 이 취소의 거래 키를 싣지 않는다. */
        data object ApproveWithoutTransactionKey : RefundScript

        /**
         * 선점 뒤에 결제사 쪽 어댑터가 식별자 모양을 거절한다(토스의 `TossIdentifierRejectedException` 처럼 `COMMON.VALIDATION_FAILED` 로 던지는 호출자 잘못) —
         * 결제사를 부르지 못했으므로 장부에게는 "모름"이어야 하고 HTTP 응답이 400 이 되면 안 된다.
         */
        data object RejectIdentifier : RefundScript

        /** 결제사가 "처리 중"이라 답한다 — [applyLate] 를 부를 때까지 적용되지 않는다. */
        data object AnswerPending : RefundScript
    }

    data class RefundCall(
        val providerPaymentId: String,
        val amount: PaymentAmount,
        val reason: String?,
        val idempotencyKey: IdempotencyKey?,
    )

    data class ProviderPayment(
        val paymentKey: String,
        val orderId: String?,
        val total: PaymentAmount,
        val status: ProviderPaymentStatus,
        val card: PaymentCard?,
        val method: String? = "CARD",
        val paidAt: Instant? = null,
        /** 결제사가 아는 취소 전부(장부가 모르는 것 포함). */
        val refunds: List<ProviderRefund> = emptyList(),
    ) {
        val refundedTotal: Long get() = refunds.sumOf { it.amount.amount }
        val remaining: PaymentAmount get() = PaymentAmount(total.amount - refundedTotal, total.currency)
    }

    data class ConfirmCall(
        val providerPaymentId: String,
        val amount: PaymentAmount,
        val idempotencyKey: IdempotencyKey?,
        val merchantReferenceId: String?,
    )

    /** 서명이 있는 결제사처럼 군다 — true 면 [SIGNATURE_HEADER] 가 [VALID_SIGNATURE] 가 아닌 웹훅을 진위 거절한다. 기본 false(토스처럼 서명 없음 → 주소의 비밀 조각이 필수). */
    @Volatile
    var webhookSigned: Boolean = false

    override val signed: Boolean get() = webhookSigned

    override fun parse(delivery: WebhookDelivery): WebhookEnvelope {
        if (webhookSigned && delivery.headers[SIGNATURE_HEADER] != VALID_SIGNATURE) throw PaymentWebhookRejectedException()
        val fields = String(delivery.body, Charsets.UTF_8).lines().mapNotNull { it.split('=', limit = 2).takeIf { parts -> parts.size == 2 } }.associate { it[0] to it[1] }
        val eventType = fields["eventType"] ?: throw PaymentWebhookRejectedException()
        val eventKey = delivery.headers[DELIVERY_HEADER]?.takeIf { it.isNotBlank() }
            ?: MessageDigest.getInstance("SHA-256").digest(delivery.body).joinToString("") { "%02x".format(it) }
        if (eventType != "PAYMENT_STATUS_CHANGED") return WebhookEnvelope(eventKey = eventKey, eventType = eventType, resourceType = "unsupported", resourceId = eventKey)
        val paymentKey = fields["paymentKey"] ?: throw PaymentWebhookRejectedException()
        // 본문의 status 는 읽지 않는다 — 읽는다면 그것이 이 해석기의 버그다
        return WebhookEnvelope(eventKey = eventKey, eventType = eventType, resourceType = "payment", resourceId = paymentKey, orderId = fields["orderId"])
    }

    /**
     * 이 결제사가 보내는 웹훅 한 건. [eventKey] 가 null 이면 전달 id 헤더가 없다(본문 해시가 이벤트 키). [claimedStatus] 는 본문이 **주장하는** 상태 — 해석기가 읽지 않는다.
     * [plant] 는 본문에 심는 개인정보 같은 값(저장되지 않아야 한다).
     */
    fun webhookDelivery(
        eventKey: String? = "evt-1",
        paymentKey: String = "pay_1",
        orderId: String? = null,
        eventType: String = "PAYMENT_STATUS_CHANGED",
        claimedStatus: String = "DONE",
        plant: String? = null,
        signature: String? = null,
    ): WebhookDelivery {
        val body = buildString {
            append("eventType=").append(eventType).append('\n')
            append("paymentKey=").append(paymentKey).append('\n')
            orderId?.let { append("orderId=").append(it).append('\n') }
            append("status=").append(claimedStatus).append('\n')
            plant?.let { append("buyer=").append(it).append('\n') }
        }
        val headers = buildMap {
            eventKey?.let { put(DELIVERY_HEADER, it) }
            signature?.let { put(SIGNATURE_HEADER, it) }
        }
        return WebhookDelivery(headers, body.toByteArray(Charsets.UTF_8))
    }

    private val lock = Any()
    private val payments = LinkedHashMap<String, ProviderPayment>()
    private val scripts = ArrayDeque<ConfirmScript>()
    private val refundScripts = ArrayDeque<RefundScript>()
    private val lateRefunds = ArrayList<RefundCall>()
    private val refundsByKey = HashMap<String, ProviderRefund>()
    private var transactionSequence = 0
    private var lookupFailures = 0
    private var lookupFailure: () -> Exception = { serverError() }

    val confirmCalls = CopyOnWriteArrayList<ConfirmCall>()

    /** 승인 요청 전체(승인 토큰 · 계정 포함) - [confirmCalls] 가 담지 않는 칸을 보는 시험용. */
    val confirmRequests = CopyOnWriteArrayList<PaymentConfirmRequest>()

    /** 결제창을 열 때 결제사가 거래 번호를 먼저 정하는 결제사(카카오페이)처럼 군다 - 주문마다 돌려줄 번호. 기본은 정하지 않는다. */
    @Volatile var checkoutPaymentId: ((CheckoutOrder) -> String?)? = null
    val lookupCalls = CopyOnWriteArrayList<PaymentLookupQuery>()
    val checkoutCalls = CopyOnWriteArrayList<CheckoutOrder>()
    val refundCalls = CopyOnWriteArrayList<RefundCall>()

    /** 환불 요청 전체(`recurring` 포함) - [refundCalls] 가 담지 않는 칸을 보는 시험용. */
    val refundRequests = CopyOnWriteArrayList<PaymentRefundRequest>()

    /** 결제사 코드 → `REFUND_DECLINED` 분류 — 실제로는 결제사 모듈이 정한다. */
    @Volatile var declineCategories: Map<String, RefundDeclineCategory> =
        mapOf("NOT_ALLOWED_PARTIAL_REFUND" to RefundDeclineCategory.PARTIAL_NOT_ALLOWED, "EXCEED_MAX_REFUND_DUE" to RefundDeclineCategory.PERIOD_EXPIRED)

    override fun refundDeclineCategory(providerCode: String?): RefundDeclineCategory = declineCategories[providerCode] ?: RefundDeclineCategory.OTHER

    /** 결제사 코드 → `DECLINED` 의 `data.category` — 실제로는 결제사 모듈이 정한다. */
    @Volatile var confirmDeclineCategories: Map<String, ConfirmDeclineCategory> =
        mapOf("REJECT_CARD_COMPANY" to ConfirmDeclineCategory.CARD_DECLINED, "EXCEED_MAX_AMOUNT" to ConfirmDeclineCategory.LIMIT_OR_BALANCE)

    override fun confirmDeclineCategory(providerCode: String?): ConfirmDeclineCategory = confirmDeclineCategories[providerCode] ?: ConfirmDeclineCategory.OTHER

    /** 환불 호출 안에서(결제사 쪽 상태를 바꾸기 전에) 실행된다 — 동시성 시험이 경주 창을 넓히는 데 쓴다. */
    @Volatile var duringRefund: (() -> Unit)? = null

    /** 열린 장부 트랜잭션 안에서 불려 거절한 호출의 이름 — 시험이 "한 번도 없다"를 단정한다. */
    val callsInsideTransaction = CopyOnWriteArrayList<String>()

    /**
     * true 면 승인 응답이 카드 · 승인 시각을 직접 싣는다(토스처럼) — 장부가 추가 조회를 하지 않아야 한다.
     * 기본 false = 응답에 없는 결제사(조회로 채운다).
     */
    @Volatile var confirmCarriesDetails: Boolean = false

    /** 조회가 답을 정한 직후(돌려주기 전)에 실행된다 — "조회한 뒤 줄을 잠그기 전" 사이에 다른 일이 끼는 경주를 결정적으로 만든다. */
    @Volatile var afterLookup: (() -> Unit)? = null

    /** 승인 호출 안에서(결제사 쪽 상태를 바꾸기 전에) 실행된다 — 동시성 시험이 경주 창을 넓히는 데 쓴다. */
    @Volatile var duringConfirm: (() -> Unit)? = null

    /** 브라우저가 결제창에서 인증을 마친 상태 — 결제사에 `IN_PROGRESS` 결제가 생긴다. */
    fun authenticate(
        paymentKey: String,
        orderId: String,
        total: PaymentAmount,
        card: PaymentCard? = PaymentCard("HYUNDAI", "1234"),
    ) = synchronized(lock) {
        payments[paymentKey] = ProviderPayment(paymentKey, orderId, total, ProviderPaymentStatus.IN_PROGRESS, card)
    }

    /** 다음 승인 호출들의 대본(차례로 소비한다). */
    fun script(vararg next: ConfirmScript) = synchronized(lock) { scripts.addAll(next) }

    /** 앞으로 [times] 번의 조회가 실패한다(기본은 5xx). */
    fun failLookups(times: Int, error: () -> Exception = { serverError() }) = synchronized(lock) {
        lookupFailures = times
        lookupFailure = error
    }

    /** 남은 승인 · 환불 대본, 아직 적용되지 않은 "한참 뒤" 환불, 남은 조회 실패를 비운다 — 컨텍스트 하나를 나눠 쓰는 시험이 시험 사이에 부른다(호출 기록 · 결제사 쪽 결제는 건드리지 않는다). */
    fun clearScripts() = synchronized(lock) {
        scripts.clear()
        refundScripts.clear()
        lateRefunds.clear()
        lookupFailures = 0
    }

    /** 결제사가 (한참 뒤) 결제를 끝낸다. */
    fun settle(paymentKey: String) = change(paymentKey) { it.copy(status = ProviderPaymentStatus.PAID, paidAt = now()) }

    /** 결제사가 결제를 실패(ABORTED)로 닫는다. */
    fun abort(paymentKey: String) = change(paymentKey) { it.copy(status = ProviderPaymentStatus.FAILED) }

    /** 결제사가 인증 만료로 닫는다. */
    fun expire(paymentKey: String) = change(paymentKey) { it.copy(status = ProviderPaymentStatus.EXPIRED) }

    fun payment(paymentKey: String): ProviderPayment? = synchronized(lock) { payments[paymentKey] }

    /** 결제사가 이 결제를 [status] 로 말하게 한다(예: 장부는 결제됐다는데 결제사는 실패 · 만료 · 진행 중이라고 답하는 어긋남). */
    fun setProviderStatus(paymentKey: String, status: ProviderPaymentStatus) = synchronized(lock) {
        payments[paymentKey] = checkNotNull(payments[paymentKey]) { "no such provider payment in the script" }.copy(status = status)
    }

    /** 다음 환불 호출들의 대본(차례로 소비한다). 대본이 없으면 환불된다. */
    fun scriptRefund(vararg next: RefundScript) = synchronized(lock) { refundScripts.addAll(next) }

    /** 결제사 관리 화면에서 취소했다 — 장부는 모른다. 결제사 쪽에 새 취소(거래 키 포함)가 생긴다. */
    fun consoleCancel(paymentKey: String, amount: Long): ProviderRefund = synchronized(lock) { cancel(paymentKey, amount) }

    /** "한참 뒤에 적용되는" 환불([RefundScript.TimeoutAppliedLater] · [RefundScript.AnswerPending])이 이제 적용된다. */
    fun applyLate() = synchronized(lock) {
        lateRefunds.toList().forEach { call ->
            lateRefunds.remove(call)
            cancel(call.providerPaymentId, call.amount.amount, call.idempotencyKey)
        }
    }

    /** 결제사 쪽의 취소 거래 키가 [n] 번째일 때의 값. */
    fun transactionKey(n: Int): String = "txn_$n"

    private fun cancel(paymentKey: String, amount: Long, key: IdempotencyKey? = null): ProviderRefund {
        val current = checkNotNull(payments[paymentKey]) { "no such provider payment in the script" }
        val cancelled = ProviderRefund("txn_${++transactionSequence}", PaymentAmount(amount, current.total.currency), now())
        payments[paymentKey] = current.copy(refunds = current.refunds + cancelled)
        key?.let { refundsByKey[it.value] = cancelled }
        return cancelled
    }

    // --- 자동결제(빌링) 대역 — 카드 등록(`issue`) · 승인(`charge`) · 삭제(`revoke`). 승인은 [ConfirmScript] 대본을 그대로 쓰고(대본이 없으면 승인), 결제사 쪽 결제를 `payments` 에 남겨 조회가 말한다. ---

    /** `issue` 가 받은 값 · 부른 횟수. 발급마다 새 토큰(`bkey_1`, `bkey_2` …)이 나온다. */
    val issueCalls = CopyOnWriteArrayList<Map<String, Any?>>()
    val chargeCalls = CopyOnWriteArrayList<BillingChargeRequest>()
    val revokeCalls = CopyOnWriteArrayList<Pair<String, String>>()
    private var billingSequence = 0
    private val chargesByKey = HashMap<String, PaymentOperationResult>()

    /** 발급 안에서(결제사 쪽 상태를 만들기 전에) 실행된다 — 동시성 시험이 경주 창을 넓히는 데 쓴다. */
    @Volatile var duringIssue: (() -> Unit)? = null

    /** 승인 호출 안에서(결제사 쪽 상태를 바꾸기 전에) 실행된다. */
    @Volatile var duringCharge: (() -> Unit)? = null

    // --- 서버가 먼저 예약하는 결제사 · 등록이 곧 첫 결제인 결제사(카카오페이 모양)를 흉내 내는 스위치. 기본은 꺼짐(토스 모양) ---

    /** true 면 `prepareBilling` 이 거래 번호(`ref_1` …)를 낸다 - 구독 줄의 첫 거래 번호에 박힌다. */
    @Volatile var prepareReferences = false
    val prepareCalls = CopyOnWriteArrayList<BillingPrepareRequest>()
    private var prepareSequence = 0

    /** 있으면 `registrationWindowLifetime` - 같은 키의 재전송이 이 시간 안에서는 저장된 첫 창을 돌려받는다(카카오페이 모양). */
    @Volatile var windowLifetime: Duration? = null
    override val registrationWindowLifetime: Duration? get() = windowLifetime

    /** true 면 `registrationCharges` - `activate` 가 `issue` 가 아니라 `registerWithCharge` 를 부른다. */
    @Volatile var registering = false
    override val registrationCharges: Boolean get() = registering
    val registerCalls = CopyOnWriteArrayList<RegisterWithChargeRequest>()

    /** true 면 `registerWithCharge` 가 SID 를 못 읽는다(돈은 움직였다). */
    @Volatile var registerWithoutToken = false

    /** false 면 주문 번호만 든 조회는 결제사를 모른다고 답한다(카카오페이처럼 거래 번호로만 조회된다). */
    @Volatile var orderLookupAnswers = true
    override val answersByOrderId: Boolean get() = orderLookupAnswers

    /** `issue` 가 받은 예약 번호(`reference`). */
    val issueReferences = CopyOnWriteArrayList<String?>()

    /** 있으면 다음 `charge` 가 던지지 않고 「모름」(`UNKNOWN`) 결과와 이 결제 번호를 돌려준다(네이버: 승인이 모르는 답 + 예약으로 받은 paymentId). */
    @Volatile var unknownChargeKey: String? = null

    override fun prepareBilling(request: BillingPrepareRequest): PreparedBilling? {
        refuseInsideTransaction("prepareBilling")
        prepareCalls += request
        val reference = if (prepareReferences) synchronized(lock) { "ref_${++prepareSequence}" } else null
        return billingSetup(request.customerKey)?.let { PreparedBilling(if (reference == null) it else it.copy(attributes = mapOf("pcUrl" to "https://pay.example/$reference")), reference) }
    }

    override fun issue(customerKey: String, providerPayload: Map<String, Any?>, reference: String?): BillingIssue {
        issueReferences += reference
        return issue(customerKey, providerPayload)
    }

    override fun registerWithCharge(request: RegisterWithChargeRequest): RegisteredCharge {
        refuseInsideTransaction("registerWithCharge")
        val script = synchronized(lock) {
            registerCalls += request
            scripts.removeFirstOrNull() ?: ConfirmScript.Approve
        }
        val asCharge = BillingChargeRequest("", request.customerKey, request.orderId, request.orderName, request.amount, request.idempotencyKey)
        val key = request.reference
        val result = when (script) {
            is ConfirmScript.Approve -> charged(asCharge, request.amount, key)
            is ConfirmScript.ApproveWithTotal -> charged(asCharge, script.total, key)
            is ConfirmScript.Decline -> throw refusal(script.code)
            is ConfirmScript.TimeoutApplied -> {
                charged(asCharge, script.total ?: request.amount, key)
                throw timeout()
            }
            is ConfirmScript.TimeoutNotApplied, is ConfirmScript.TimeoutAppliedLater -> {
                register(asCharge, request.amount, ProviderPaymentStatus.IN_PROGRESS, key)
                throw timeout()
            }
            is ConfirmScript.ServerError -> throw serverError()
        }
        val issue = if (registerWithoutToken) null else BillingIssue("sid_${synchronized(lock) { ++billingSequence }}", PaymentCard("SHINHAN", null))
        return RegisteredCharge(issue, result)
    }

    override fun billingSetup(customerKey: String): BillingSetup? =
        if (!checkoutReady) null else BillingSetup(providerId, "SCRIPTED_BILLING_AUTH", mode == PaymentMode.TEST, "scripted_ck", customerKey)

    override fun issue(customerKey: String, providerPayload: Map<String, Any?>): BillingIssue {
        refuseInsideTransaction("issue")
        duringIssue?.invoke()
        synchronized(lock) {
            issueCalls += providerPayload
            return BillingIssue("bkey_${++billingSequence}", PaymentCard("HYUNDAI", "1234"))
        }
    }

    override fun charge(request: BillingChargeRequest): PaymentOperationResult {
        refuseInsideTransaction("charge")
        val script = synchronized(lock) {
            chargeCalls += request
            scripts.removeFirstOrNull() ?: ConfirmScript.Approve
        }
        duringCharge?.invoke()
        unknownChargeKey?.let { key ->
            return PaymentOperationResult(provider = providerId, providerPaymentId = key, status = PaymentOperationStatus.UNKNOWN, trace = ProviderTrace(provider = providerId, rawStatus = "UNKNOWN"))
        }
        synchronized(lock) { chargesByKey[request.idempotencyKey.value] }?.let { return it } // 같은 멱등 키는 결제사가 한 번으로 본다
        return when (script) {
            is ConfirmScript.Approve -> charged(request, request.amount)
            is ConfirmScript.ApproveWithTotal -> charged(request, script.total)
            is ConfirmScript.Decline -> throw refusal(script.code)
            is ConfirmScript.TimeoutApplied -> {
                charged(request, script.total ?: request.amount)
                throw timeout()
            }
            is ConfirmScript.TimeoutNotApplied, is ConfirmScript.TimeoutAppliedLater -> {
                register(request, request.amount, ProviderPaymentStatus.IN_PROGRESS)
                throw timeout()
            }
            is ConfirmScript.ServerError -> throw serverError()
        }
    }

    override fun revoke(customerKey: String, token: String) {
        refuseInsideTransaction("revoke")
        synchronized(lock) { revokeCalls += customerKey to token }
    }

    /** 결제사 쪽에 이 승인으로 생긴 결제가 있는가(주문 번호로) — 이중 승인 시험이 센다. */
    fun providerPaymentsOfOrder(orderId: String): List<ProviderPayment> = synchronized(lock) { payments.values.filter { it.orderId == orderId } }

    private fun register(request: BillingChargeRequest, total: PaymentAmount, status: ProviderPaymentStatus, key: String? = null): ProviderPayment = synchronized(lock) {
        val existing = payments.values.lastOrNull { it.orderId == request.orderId }
        existing ?: ProviderPayment(key ?: "pay_bill_${payments.size + 1}", request.orderId, total, status, PaymentCard("HYUNDAI", "1234"), paidAt = now().takeIf { status == ProviderPaymentStatus.PAID })
            .also { payments[it.paymentKey] = it }
    }

    private fun charged(request: BillingChargeRequest, total: PaymentAmount, key: String? = null): PaymentOperationResult = synchronized(lock) {
        val payment = register(request, total, ProviderPaymentStatus.PAID, key).let { registered ->
            registered.copy(total = total, status = ProviderPaymentStatus.PAID, paidAt = now()).also { payments[it.paymentKey] = it }
        }
        PaymentOperationResult(
            provider = providerId,
            providerPaymentId = payment.paymentKey,
            status = PaymentOperationStatus.CONFIRMED,
            amount = payment.total,
            trace = ProviderTrace(provider = providerId, rawStatus = "DONE", metadata = mapOf("orderId" to request.orderId)),
            attributes = mapOf("method" to "CARD"),
            card = payment.card,
            paidAt = payment.paidAt,
        ).also { chargesByKey[request.idempotencyKey.value] = it }
    }

    override fun billingDeclineCategory(providerCode: String?): ConfirmDeclineCategory = confirmDeclineCategories[providerCode] ?: ConfirmDeclineCategory.OTHER

    /** [Error] 로 던진다 — 호출한 쪽의 `catch (e: Exception)` 이 "모름"으로 삼키지 못하게. */
    private fun refuseInsideTransaction(call: String) {
        if (openTransaction()) {
            callsInsideTransaction += call
            throw AssertionError("$call was called inside an open ledger transaction - provider calls must stay outside locks and transactions")
        }
    }

    override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
        refuseInsideTransaction("confirm")
        val script = synchronized(lock) {
            confirmCalls += ConfirmCall(request.providerPaymentId, request.amount, request.idempotencyKey, request.merchantReferenceId)
            confirmRequests += request
            scripts.removeFirstOrNull() ?: ConfirmScript.Approve
        }
        duringConfirm?.invoke()
        return when (script) {
            is ConfirmScript.Approve -> approve(request, request.amount)
            is ConfirmScript.ApproveWithTotal -> approve(request, script.total)
            is ConfirmScript.Decline -> throw refusal(script.code)
            is ConfirmScript.TimeoutApplied -> {
                approve(request, script.total ?: request.amount)
                throw timeout()
            }
            is ConfirmScript.TimeoutNotApplied, is ConfirmScript.TimeoutAppliedLater -> {
                ensureRegistered(request, request.amount)
                throw timeout()
            }
            is ConfirmScript.ServerError -> throw serverError()
        }
    }

    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? {
        refuseInsideTransaction("lookup")
        val found = synchronized(lock) {
            lookupCalls += query
            if (lookupFailures > 0) {
                lookupFailures--
                throw lookupFailure()
            }
            query.providerPaymentId?.let { payments[it] } ?: if (orderLookupAnswers) query.orderId?.let { id -> payments.values.lastOrNull { it.orderId == id } } else null
        }
        afterLookup?.invoke()
        if (found == null) return null
        val status = when {
            found.status != ProviderPaymentStatus.PAID || found.refunds.isEmpty() -> found.status
            found.refundedTotal >= found.total.amount -> ProviderPaymentStatus.REFUNDED
            else -> ProviderPaymentStatus.PARTIALLY_REFUNDED
        }
        return PaymentSnapshot(
            providerPaymentId = found.paymentKey,
            orderId = found.orderId,
            status = status,
            total = found.total,
            remaining = found.remaining,
            method = found.method,
            paidAt = found.paidAt,
            refunds = found.refunds,
            card = found.card,
        )
    }

    override fun refund(request: PaymentRefundRequest): PaymentOperationResult {
        refuseInsideTransaction("refund")
        val call = RefundCall(request.providerPaymentId, request.amount, request.reason, request.idempotencyKey)
        val script = synchronized(lock) {
            refundCalls += call
            refundRequests += request
            refundScripts.removeFirstOrNull() ?: RefundScript.Approve
        }
        duringRefund?.invoke()
        return synchronized(lock) {
            // 같은 멱등 키는 결제사가 한 번으로 본다 — 앞선 답을 그대로 돌려준다
            request.idempotencyKey?.let { key -> refundsByKey[key.value] }?.let { return@synchronized answer(request, it, withKey = true) }
            when (script) {
                is RefundScript.Approve -> answer(request, apply(request, request.amount.amount), withKey = true)
                is RefundScript.ApproveOtherAmount -> answer(request, apply(request, script.amount.amount), withKey = true)
                is RefundScript.ApproveWithoutTransactionKey -> answer(request, apply(request, request.amount.amount), withKey = false)
                is RefundScript.Decline -> throw providerException(status = 400, code = script.code, definitive = true)
                is RefundScript.TimeoutApplied -> {
                    apply(request, request.amount.amount)
                    throw timeout()
                }
                is RefundScript.TimeoutNotApplied -> throw timeout()
                is RefundScript.TimeoutAppliedLater -> {
                    lateRefunds += call
                    throw timeout()
                }
                is RefundScript.ServerError -> throw serverError()
                is RefundScript.RejectIdentifier -> throw ApplicationException("The payment identifier is not in a valid form.", PlatformErrorCode.VALIDATION_FAILED)
                is RefundScript.AnswerPending -> {
                    lateRefunds += call
                    PaymentOperationResult(
                        provider = providerId,
                        providerPaymentId = request.providerPaymentId,
                        status = PaymentOperationStatus.PENDING,
                        amount = request.amount,
                        trace = ProviderTrace(provider = providerId, rawStatus = "IN_PROGRESS"),
                    )
                }
            }
        }
    }

    /** 결제사 쪽에서 취소한다. 취소 가능 금액을 넘으면 결제사처럼 거절하되 **확정이 아니다**(앞선 요청의 결과일 수 있는 코드). */
    private fun apply(request: PaymentRefundRequest, amount: Long): ProviderRefund {
        val current = checkNotNull(payments[request.providerPaymentId]) { "no such provider payment in the script" }
        if (amount > current.remaining.amount) throw providerException(status = 400, code = "EXCEED_CANCEL_LIMIT", definitive = false)
        return cancel(request.providerPaymentId, amount, request.idempotencyKey)
    }

    private fun answer(request: PaymentRefundRequest, cancelled: ProviderRefund, withKey: Boolean): PaymentOperationResult {
        val payment = checkNotNull(payments[request.providerPaymentId])
        return PaymentOperationResult(
            provider = providerId,
            providerPaymentId = request.providerPaymentId,
            status = PaymentOperationStatus.REFUNDED,
            amount = cancelled.amount,
            trace = ProviderTrace(provider = providerId, providerOperationId = cancelled.providerRefundId.takeIf { withKey }, rawStatus = "CANCELED"),
            providerOperationId = cancelled.providerRefundId.takeIf { withKey },
            remaining = payment.remaining,
        )
    }

    /** false 면 결제창 값을 낼 수 없는 결제사처럼 군다(공개 키가 비었을 때의 토스) — `GET /config` 의 `available` 이 false 가 된다. 기본 true. */
    @Volatile var checkoutReady: Boolean = true

    override fun canOpenCheckout(): Boolean = checkoutReady

    /** 결제사가 [words] 라는 문장과 함께 [status] 로 답한 오류 — 그 문장이 응답의 `detail` 로 새지 않는지 재는 시험용. */
    fun providerErrorSaying(status: Int, words: String) = PaymentProviderException(
        providerError = PaymentProviderError(provider = providerId, code = "PROVIDER_ERROR", message = words, trace = ProviderTrace(provider = providerId), upstreamStatus = status, retryable = false),
        clientName = providerId,
        method = "GET",
        uri = ENDPOINT,
    )

    override fun checkout(order: CheckoutOrder): CheckoutInstruction {
        refuseInsideTransaction("checkout")
        checkoutCalls += order
        return CheckoutInstruction(
            provider = providerId,
            kind = "SCRIPTED_WINDOW",
            testMode = mode == PaymentMode.TEST,
            orderId = order.orderId,
            orderName = order.orderName,
            amount = order.amount,
            clientKey = "scripted_ck",
            providerPaymentId = checkoutPaymentId?.invoke(order),
        )
    }

    private fun approve(request: PaymentConfirmRequest, total: PaymentAmount): PaymentOperationResult {
        val payment = synchronized(lock) {
            val existing = payments[request.providerPaymentId]
            val settled = (existing ?: ProviderPayment(request.providerPaymentId, request.merchantReferenceId, total, ProviderPaymentStatus.IN_PROGRESS, PaymentCard("HYUNDAI", "1234")))
                .copy(total = total, status = ProviderPaymentStatus.PAID, paidAt = now())
            payments[request.providerPaymentId] = settled
            settled
        }
        return PaymentOperationResult(
            provider = providerId,
            providerPaymentId = payment.paymentKey,
            status = PaymentOperationStatus.CONFIRMED,
            amount = payment.total,
            trace = ProviderTrace(provider = providerId, rawStatus = "DONE"),
            attributes = mapOf("method" to "CARD"),
            card = payment.card.takeIf { confirmCarriesDetails },
            paidAt = payment.paidAt.takeIf { confirmCarriesDetails },
        )
    }

    private fun ensureRegistered(request: PaymentConfirmRequest, total: PaymentAmount) = synchronized(lock) {
        payments.putIfAbsent(
            request.providerPaymentId,
            ProviderPayment(request.providerPaymentId, request.merchantReferenceId, total, ProviderPaymentStatus.IN_PROGRESS, PaymentCard("HYUNDAI", "1234")),
        )
    }

    private fun change(paymentKey: String, update: (ProviderPayment) -> ProviderPayment) = synchronized(lock) {
        val current = checkNotNull(payments[paymentKey]) { "no such provider payment in the script" }
        payments[paymentKey] = update(current)
    }

    private fun timeout() = ExternalHttpTimeoutException(providerId, "POST", ENDPOINT)

    private fun refusal(code: String) = providerException(status = 400, code = code, definitive = true)

    private fun serverError() = providerException(status = 503, code = "PROVIDER_ERROR", definitive = false, retryable = true)

    private fun providerException(status: Int, code: String, definitive: Boolean, retryable: Boolean = false) = PaymentProviderException(
        providerError = PaymentProviderError(
            provider = providerId,
            code = code,
            trace = ProviderTrace(provider = providerId),
            upstreamStatus = status,
            retryable = retryable,
            definitive = definitive,
        ),
        clientName = providerId,
        method = "POST",
        uri = ENDPOINT,
    )

    companion object {
        const val DELIVERY_HEADER = "x-scripted-delivery-id"
        const val SIGNATURE_HEADER = "x-scripted-signature"
        const val VALID_SIGNATURE = "valid"
        private val ENDPOINT: URI = URI.create("https://scripted.example.test/v1/payments/confirm")
    }
}
