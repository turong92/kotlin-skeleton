package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.PaymentBuyerCountryGateway
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.HostedSubscriptionRequest
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentLookupQuery
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentRefundGateway
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.PaymentSnapshot
import dev.sumin.skeleton.payment.ProviderPaymentStatus
import dev.sumin.skeleton.payment.ProviderRefund
import dev.sumin.skeleton.payment.ProviderTrace
import dev.sumin.skeleton.payment.PushedPayment
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import java.time.Instant
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

/**
 * 서명된 웹훅이 곧 사실이고 거래를 서버가 먼저 만드는 결제사(Paddle 모양)의 대역. 웹훅 본문은 사건 id 한 줄이고, 사건은 [delivery] 가 등록한다.
 * 환불 · 조회 능력도 Paddle 처럼 거래 번호로만 답한다: 결제 사건([PushedPayment])을 [delivery] 로 등록하면 그 거래가 결제사 쪽에 생기고, [refund] 는 [refundScript] 대로
 * 환불([adjustments])을 만든다(바로 이뤄지거나 · 검토 중이거나 · 거절 · 모름). 결제사의 결정은 [decide] 로 내리고 같은 내용을 웹훅 사건([PushedRefund])으로 [delivery] 에 실을 수 있다.
 */
class FakePushGateway(override val providerId: String = "paddle", override val mode: PaymentMode = PaymentMode.TEST) :
    PaymentPushGateway, PaymentHostedSubscriptionGateway, PaymentTransactionGateway, PaymentCheckoutGateway, PaymentBuyerCountryGateway, PaymentRefundGateway, PaymentLookupGateway {
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

    override val answersByOrderId: Boolean = false

    /** 결제사 쪽의 환불 한 건 - [status] 는 Paddle 의 `pending_approval` · `approved` · `rejected`. */
    class Adjustment(val id: String, val amount: PaymentAmount, @Volatile var status: String, val chargeback: Boolean = false, val warning: Boolean = false)

    private class Txn(val total: PaymentAmount, val paidAt: Instant, val adjustments: MutableList<Adjustment> = CopyOnWriteArrayList())

    private val txns = ConcurrentHashMap<String, Txn>()
    private val adjustmentSeq = AtomicInteger()

    /** 이 환불 호출이 어떻게 끝나는가. */
    enum class RefundScript { APPROVED, PENDING_APPROVAL, DECLINED, UNKNOWN_BUT_PENDING, UNKNOWN_NOT_APPLIED }

    @Volatile
    var refundScript: RefundScript = RefundScript.APPROVED

    val refundCalls = CopyOnWriteArrayList<PaymentRefundRequest>()
    val lookupCalls = CopyOnWriteArrayList<PaymentLookupQuery>()

    fun adjustments(txn: String): List<Adjustment> = txns[txn]?.adjustments.orEmpty().toList()

    /** 결제사가 검토 중이던 환불에 결정을 내린다(`approved` · `rejected`). */
    fun decide(txn: String, adjustmentId: String, status: String) {
        txns.getValue(txn).adjustments.first { it.id == adjustmentId }.status = status
    }

    /** 결제사 대시보드에서 낸 환불 - 장부는 모른다. */
    fun consoleRefund(txn: String, amount: PaymentAmount, status: String = "approved", chargeback: Boolean = false, warning: Boolean = false): Adjustment =
        Adjustment("adj_c${adjustmentSeq.incrementAndGet()}", amount, status, chargeback, warning).also { txns.getValue(txn).adjustments += it }

    override fun refund(request: PaymentRefundRequest): PaymentOperationResult {
        refundCalls += request
        val txn = txns[request.providerPaymentId] ?: throw PaymentProviderException(
            PaymentProviderError(providerId, "not_found", null, ProviderTrace(providerId), 404, retryable = false, definitive = true),
            "fake-paddle", "POST", java.net.URI.create("http://paddle.invalid/adjustments"),
        )
        fun adjust(status: String) = Adjustment("adj_s${adjustmentSeq.incrementAndGet()}", request.amount, status).also { txn.adjustments += it }
        return when (refundScript) {
            RefundScript.APPROVED -> adjust("approved").let { answer(request, it, PaymentOperationStatus.REFUNDED) }
            RefundScript.PENDING_APPROVAL -> adjust("pending_approval").let { answer(request, it, PaymentOperationStatus.PENDING) }
            RefundScript.DECLINED -> throw PaymentProviderException(
                PaymentProviderError(providerId, "adjustment_transaction_invalid_status_for_refund", null, ProviderTrace(providerId), 400, retryable = false, definitive = true),
                "fake-paddle", "POST", java.net.URI.create("http://paddle.invalid/adjustments"),
            )
            RefundScript.UNKNOWN_BUT_PENDING -> { adjust("pending_approval"); throw IllegalStateException("planted timeout") }
            RefundScript.UNKNOWN_NOT_APPLIED -> throw IllegalStateException("planted timeout")
        }
    }

    private fun answer(request: PaymentRefundRequest, adjustment: Adjustment, status: PaymentOperationStatus) = PaymentOperationResult(
        provider = providerId, providerPaymentId = request.providerPaymentId, status = status, amount = adjustment.amount,
        trace = ProviderTrace(providerId, providerOperationId = adjustment.id),
    )

    override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? {
        lookupCalls += query
        val id = query.providerPaymentId ?: return null
        val txn = txns[id] ?: return null
        val approved = txn.adjustments.filter { it.status == "approved" }
        val refunded = approved.sumOf { it.amount.amount }
        val remaining = txn.total.amount - refunded
        return PaymentSnapshot(
            providerPaymentId = id, orderId = null,
            status = when { refunded == 0L -> ProviderPaymentStatus.PAID; remaining <= 0 -> ProviderPaymentStatus.REFUNDED; else -> ProviderPaymentStatus.PARTIALLY_REFUNDED },
            total = txn.total, remaining = PaymentAmount(remaining, txn.total.currency), method = null, paidAt = txn.paidAt,
            refunds = approved.map { ProviderRefund(it.id, it.amount, txn.paidAt, it.chargeback, it.warning) },
            pendingRefunds = txn.adjustments.filter { it.status == "pending_approval" }.map { ProviderRefund(it.id, it.amount, txn.paidAt) },
        )
    }

    /** 결제사 쪽에 거래가 있다 - 결제 사건 없이도 환불 시험을 하려고. */
    fun completeTransaction(txn: String, total: PaymentAmount, at: Instant = Instant.parse("2026-10-13T09:59:00Z")) {
        txns.putIfAbsent(txn, Txn(total, at))
    }

    fun delivery(event: PushedPaymentEvent, signature: String = "ok"): WebhookDelivery {
        events[event.eventId] = event
        if (event is PushedPayment) txns.putIfAbsent(event.providerPaymentId, Txn(event.total, event.paidAt))
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
