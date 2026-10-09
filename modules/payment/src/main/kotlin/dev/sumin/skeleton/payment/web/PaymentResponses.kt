package dev.sumin.skeleton.payment.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.OperatorOrderDetail
import dev.sumin.skeleton.payment.OperatorOrderView
import dev.sumin.skeleton.payment.OrderView
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentEvent
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentWebhookEvent
import java.time.Instant

/*
 * HTTP 응답의 모양 한 곳(계약 문서 `docs/payment-http-contract.md` 가 정본). **사용자 응답**([OrderResponse] · [RefundResponse])에는 결제사 이름 · 결제사 결제 번호 · 검토 이유 · 운영자 id ·
 * 환불 줄의 실패 코드 · 두 키 · 수신함 칸이 없다 — 운영자 응답([AdminOrderResponse] · [AdminRefundResponse])에만 있다. 이 클래스들은 서블릿 없이도 쓴다(`AccountDataExporter` 가 같은 모양으로 내보낸다).
 */

data class AmountResponse(val amount: Long, val currency: String)

private fun PaymentAmount.toResponse() = AmountResponse(amount, normalizedCurrency)

/** 카드사 코드(대문자 영문)와 끝 네 글자(숫자 또는 `*`) — 각각 `null` 일 수 있다. 카드 결제가 아니거나 계정이 지워져 비워졌으면 [OrderResponse.card] 자체가 `null`. */
data class CardResponse(val issuer: String?, val last4: String?)

data class OrderResponse(
    val id: String,
    val status: String,
    val kind: String,
    val itemType: String,
    val itemRef: String,
    val quantity: Int,
    val orderName: String,
    val amount: AmountResponse,
    val refundedAmount: Long,
    val refundableAmount: Long,
    /** 열린 환불(요청됨 · 환불 중)이 있다 — 새 요청이 안 되는 이유. 어느 쪽인지는 [openRefundStatus]. */
    val refundPending: Boolean,
    /** `REQUESTED`(본인이 요청만 했다) · `PENDING`(환불 중) · 없으면 `null`. */
    val openRefundStatus: String?,
    val method: String?,
    val card: CardResponse?,
    val testMode: Boolean,
    val failureCode: String?,
    val createdAt: Instant,
    val paidAt: Instant?,
    val fulfilled: Boolean,
) {
    companion object {
        fun of(view: OrderView): OrderResponse = of(view.order, view.summary.refundableAmount, view.summary.refundPending, view.summary.openRefundStatus?.name)

        internal fun of(order: PaymentOrder, refundable: Long, refundPending: Boolean, openRefundStatus: String?) = OrderResponse(
            id = order.id,
            status = order.status.name,
            kind = order.kind.name,
            itemType = order.itemType,
            itemRef = order.itemRef,
            quantity = order.quantity,
            orderName = order.orderName,
            amount = order.amount.toResponse(),
            refundedAmount = order.refundedAmount,
            refundableAmount = refundable,
            refundPending = refundPending,
            openRefundStatus = openRefundStatus,
            method = order.method,
            card = if (order.cardIssuer == null && order.cardLast4 == null) null else CardResponse(order.cardIssuer, order.cardLast4),
            testMode = order.providerMode == PaymentMode.TEST,
            failureCode = order.failureCode,
            createdAt = order.createdAt,
            paidAt = order.paidAt,
            fulfilled = order.fulfilledAt != null,
        )
    }
}

data class RefundResponse(
    val id: String,
    val orderId: String,
    val status: String,
    val amount: AmountResponse,
    val reasonCode: String,
    val origin: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    /** `WITHDRAWN` 줄을 누가 닫았나 — `USER` 본인이 거뒀다 · `SYSTEM` 주문이 전액 환불돼 요청이 닫혔다. 다른 상태의 줄은 `null`. */
    val closedBy: String?,
) {
    companion object {
        fun of(refund: PaymentRefund) = RefundResponse(
            id = refund.id,
            orderId = refund.orderId,
            status = refund.status.name,
            amount = refund.amount.toResponse(),
            reasonCode = refund.reasonCode,
            origin = refund.origin.name,
            requestedAt = refund.requestedAt,
            completedAt = refund.completedAt,
            closedBy = closedBy(refund),
        )

        /** 시스템이 닫은 `WITHDRAWN` = `failureCode ORDER_REFUNDED`. 결제사 코드를 내보내지 않고 중립 칸 하나로만 알린다. */
        fun closedBy(refund: PaymentRefund): String? =
            if (refund.status != PaymentRefundStatus.WITHDRAWN) null else if (refund.failureCode == "ORDER_REFUNDED") "SYSTEM" else "USER"
    }
}

/** `GET /orders/{id}` — 한 건과 그 환불 줄. */
data class OrderDetailResponse(val order: OrderResponse, val refunds: List<RefundResponse>) {
    companion object {
        fun of(view: OrderView) = OrderDetailResponse(OrderResponse.of(view), view.refunds.map(RefundResponse::of))
    }
}

/** 결제창에 넘길 값 — 공개해도 되는 것만. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CheckoutResponse(
    val provider: String,
    val kind: String,
    val clientKey: String?,
    val testMode: Boolean,
    val orderId: String,
    val orderName: String,
    val amount: AmountResponse,
) {
    companion object {
        fun of(checkout: CheckoutInstruction) = CheckoutResponse(
            provider = checkout.provider,
            kind = checkout.kind,
            clientKey = checkout.clientKey,
            testMode = checkout.testMode,
            orderId = checkout.orderId,
            orderName = checkout.orderName,
            amount = checkout.amount.toResponse(),
        )
    }
}

data class CreateOrderResponse(val order: OrderResponse, val checkout: CheckoutResponse?)

data class PaymentConfigResponse(val available: Boolean, val testMode: Boolean, val refundRequestsEnabled: Boolean)

/**
 * 운영자 주문 — 사용자 응답([OrderResponse])의 칸에 더해 계정 · 결제사 · 결제사 결제 번호 · 검토 이유(파생 포함)를 싣는다.
 * **칸을 평평하게 가진다**(`@JsonUnwrapped` 로 [OrderResponse] 를 품지 않는다): OpenAPI 문서 생성기가 풀어 쓴 칸을 이름 없는 속성으로 만들어 문서가 500 이 된다.
 * 칸의 이름 · 순서는 [OrderResponse] 와 같아야 하고(앞쪽), 시험이 JSON 의 칸 순서를 글자까지 고정한다.
 */
data class AdminOrderResponse(
    val id: String,
    val status: String,
    val kind: String,
    val itemType: String,
    val itemRef: String,
    val quantity: Int,
    val orderName: String,
    val amount: AmountResponse,
    val refundedAmount: Long,
    val refundableAmount: Long,
    val refundPending: Boolean,
    val openRefundStatus: String?,
    val method: String?,
    val card: CardResponse?,
    val testMode: Boolean,
    val failureCode: String?,
    val createdAt: Instant,
    val paidAt: Instant?,
    val fulfilled: Boolean,
    val accountId: String?,
    val provider: String,
    val providerPaymentId: String?,
    val reviewReason: String?,
    /** 계정이 탈퇴한 시각 — 탈퇴 전이면 `null`. 칸은 늘 온다. */
    val accountErasedAt: Instant?,
) {
    companion object {
        fun of(view: OperatorOrderView): AdminOrderResponse {
            val o = OrderResponse.of(view.order, view.summary.refundableAmount, view.summary.refundPending, view.summary.openRefundStatus?.name)
            return AdminOrderResponse(
                id = o.id, status = o.status, kind = o.kind, itemType = o.itemType, itemRef = o.itemRef, quantity = o.quantity, orderName = o.orderName, amount = o.amount,
                refundedAmount = o.refundedAmount, refundableAmount = o.refundableAmount, refundPending = o.refundPending, openRefundStatus = o.openRefundStatus,
                method = o.method, card = o.card, testMode = o.testMode, failureCode = o.failureCode, createdAt = o.createdAt, paidAt = o.paidAt, fulfilled = o.fulfilled,
                accountId = view.order.accountId,
                provider = view.order.provider,
                providerPaymentId = view.order.providerPaymentId,
                reviewReason = view.reviewReason,
                accountErasedAt = view.order.accountErasedAt,
            )
        }
    }
}

/** 운영자 환불 — [RefundResponse] 의 칸에 더해 요청 · 결정한 사람과 줄의 이유 코드(결제사 거절 코드이거나 우리 코드). 평평한 이유는 [AdminOrderResponse] 와 같다(JSON 의 칸 순서도 같은 시험이 고정한다). */
data class AdminRefundResponse(
    val id: String,
    val orderId: String,
    val status: String,
    val amount: AmountResponse,
    val reasonCode: String,
    val origin: String,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val closedBy: String?,
    val requestedBy: String?,
    val decidedBy: String?,
    val failureCode: String?,
) {
    companion object {
        fun of(refund: PaymentRefund): AdminRefundResponse {
            val r = RefundResponse.of(refund)
            return AdminRefundResponse(
                id = r.id, orderId = r.orderId, status = r.status, amount = r.amount, reasonCode = r.reasonCode, origin = r.origin, requestedAt = r.requestedAt, completedAt = r.completedAt,
                closedBy = r.closedBy, requestedBy = refund.requestedBy, decidedBy = refund.decidedBy, failureCode = refund.failureCode,
            )
        }
    }
}

/** 이력 한 줄. [id] 는 그 주문 이력 안의 순번(1 부터, 오래된 것부터)이다. */
data class EventResponse(
    val id: Int,
    val refundId: String?,
    val type: String,
    val fromStatus: String?,
    val toStatus: String?,
    val actor: String,
    val actorId: String?,
    val providerCode: String?,
    val providerTraceId: String?,
    val traceId: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(sequence: Int, event: PaymentEvent) = EventResponse(
            id = sequence,
            refundId = event.refundId,
            type = event.type.name,
            fromStatus = event.fromStatus,
            toStatus = event.toStatus,
            actor = event.actor.name,
            actorId = event.actorId,
            providerCode = event.providerCode,
            providerTraceId = event.providerTraceId,
            traceId = event.traceId,
            createdAt = event.createdAt,
        )
    }
}

data class AdminOrderDetailResponse(val order: AdminOrderResponse, val refunds: List<AdminRefundResponse>, val events: List<EventResponse>) {
    companion object {
        fun of(detail: OperatorOrderDetail) = AdminOrderDetailResponse(
            order = AdminOrderResponse.of(detail.view),
            refunds = detail.view.refunds.map(AdminRefundResponse::of),
            events = detail.events.mapIndexed { index, event -> EventResponse.of(index + 1, event) },
        )
    }
}

/** 수신함 줄 — 본문은 없다. */
data class WebhookEventResponse(
    val id: Long,
    val provider: String,
    val eventType: String,
    val resourceType: String,
    val resourceId: String,
    val orderId: String?,
    val status: String,
    val deliveries: Int,
    val lastError: String?,
    val receivedAt: Instant,
    val processedAt: Instant?,
) {
    companion object {
        fun of(event: PaymentWebhookEvent) = WebhookEventResponse(
            id = event.id,
            provider = event.provider,
            eventType = event.eventType,
            resourceType = event.resourceType,
            resourceId = event.resourceId,
            orderId = event.orderId,
            status = event.status.name,
            deliveries = event.deliveries,
            lastError = event.lastError,
            receivedAt = event.receivedAt,
            processedAt = event.processedAt,
        )
    }
}

// --- 구독 (스펙 `2026-10-09-subscription-design.md` §7) — 빌링키 · 고객 키는 어떤 응답에도 없다(카드 등록 창 값만 예외: 고객 키는 창이 필요로 하고 공개해도 되는 값이다) ---

data class SubscriptionPlanResponse(val code: String, val name: String, val amount: AmountResponse, val period: String, val level: Int) {
    companion object {
        fun of(plan: dev.sumin.skeleton.payment.SubscriptionPlan) = SubscriptionPlanResponse(plan.code, plan.name, plan.amount.toResponse(), plan.period, plan.level)
    }
}

data class SubscriptionPlansResponse(val plans: List<SubscriptionPlanResponse>)

data class SubscriptionResponse(
    val id: String,
    /** `PENDING` · `ACTIVE` · `PAST_DUE` · `CANCELING` · `ENDED`. */
    val status: String,
    val planCode: String,
    /** 앱 플랜 목록의 이름 — 목록에서 사라진 플랜이면 코드. */
    val planName: String,
    val amount: AmountResponse,
    val period: String,
    val currentPeriodStart: Instant?,
    /** 다음 결제일 — `CANCELING` 이면 이용이 끝나는 날, `ENDED` 면 마지막 기간이 끝난 날. */
    val currentPeriodEnd: Instant?,
    /** 갱신 실패로 이번 시도가 몇 번째인가(`PAST_DUE` 에서 의미가 있다). */
    val attempt: Int,
    /** 끝난 이유(`ENDED` 일 때만) — `FIRST_PAYMENT_DECLINED` · `USER_CANCELED` · `PAYMENT_FAILED` · `ACCOUNT_ERASED` · `RENEWAL_BLOCKED` · `REFUNDED`. */
    val endReason: String?,
    val card: CardResponse?,
    val testMode: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun of(subscription: dev.sumin.skeleton.payment.PaymentSubscription, planName: String?) = SubscriptionResponse(
            id = subscription.id,
            status = subscription.status.name,
            planCode = subscription.planCode,
            planName = planName ?: subscription.planCode,
            amount = subscription.amount.toResponse(),
            period = subscription.period,
            currentPeriodStart = subscription.currentPeriodStart,
            currentPeriodEnd = subscription.currentPeriodEnd,
            attempt = subscription.attempt,
            endReason = subscription.endReason?.name,
            card = if (subscription.cardIssuer == null && subscription.cardLast4 == null) null else CardResponse(subscription.cardIssuer, subscription.cardLast4),
            testMode = subscription.providerMode == PaymentMode.TEST,
            createdAt = subscription.createdAt,
        )
    }
}

/** 카드 등록 창을 여는 값. 프론트가 [kind] 로 갈라 연다. */
data class BillingAuthResponse(val provider: String, val kind: String, val testMode: Boolean, val clientKey: String, val customerKey: String, val attributes: Map<String, String>) {
    companion object {
        fun of(setup: dev.sumin.skeleton.payment.BillingSetup) = BillingAuthResponse(setup.provider, setup.kind, setup.testMode, setup.clientKey, setup.customerKey, setup.attributes)
    }
}

data class StartSubscriptionResponse(val subscription: SubscriptionResponse, val billingAuth: BillingAuthResponse?)

/** `GET /subscriptions/me` — 지금 구독(없으면 `null`)과 최근 결제 다섯 건. */
data class MySubscriptionResponse(val subscription: SubscriptionResponse?, val orders: List<OrderResponse>)
