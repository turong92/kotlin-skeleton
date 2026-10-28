package dev.sumin.skeleton.payment.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.payment.CheckoutInstruction
import dev.sumin.skeleton.payment.DiscountDraft
import dev.sumin.skeleton.payment.OrderDiscount
import dev.sumin.skeleton.payment.OrderQuote
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
import dev.sumin.skeleton.payment.RefundOrderInfo
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
    /** 간편결제의 브랜드 코드(`KAKAOPAY` · `NAVERPAY` · `TOSSPAY` …) - 경로(결제사)와 별개인 중립 코드다(카드사 코드처럼). 간편결제가 아니거나 모르면(옛 주문 포함) `null`이고 화면은 「간편결제」라 쓴다. */
    val easyPayBrand: String?,
    val testMode: Boolean,
    val failureCode: String?,
    val createdAt: Instant,
    val paidAt: Instant?,
    val fulfilled: Boolean,
    /** 할인 전 금액(`amount` 는 할인 뒤) - 할인도 면제도 없는 주문은 `null`. 면제 주문은 카탈로그 가격(얼마어치를 면제했나). */
    val originalAmount: Long?,
    /** 깎인 금액의 합(면제는 할인이 아니라 0). */
    val discountAmount: Long,
    val discounts: List<DiscountLineResponse>,
    /** `PROVIDER`(결제사가 받았다) · `FREE`(쿠폰으로 0원 - 결제사 없이 끝남) · `EXEMPT`(결제 면제 - 결제사 없이 끝남). `FREE` · `EXEMPT` 는 처음부터 `PAID`, 결제창 없음, `refundableAmount` 0. */
    val settlement: String,
) {
    companion object {
        fun of(view: OrderView): OrderResponse = of(view.order, view.summary.refundableAmount, view.summary.refundPending, view.summary.openRefundStatus?.name, view.discounts)

        internal fun of(order: PaymentOrder, refundable: Long, refundPending: Boolean, openRefundStatus: String?, discounts: List<OrderDiscount> = emptyList()) = OrderResponse(
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
            easyPayBrand = order.easyPayBrand,
            testMode = order.providerMode == PaymentMode.TEST,
            failureCode = order.failureCode,
            createdAt = order.createdAt,
            paidAt = order.paidAt,
            fulfilled = order.fulfilledAt != null,
            originalAmount = order.originalAmount,
            discountAmount = order.discountAmount,
            discounts = discounts.map(DiscountLineResponse::of),
            settlement = order.settlement.name,
        )
    }
}

/** 주문에 붙은 할인 한 줄 - [type] 은 할인 수단(`coupon`), [ref] 는 그 수단 안의 식별(쿠폰 id), [amount] 는 깎인 금액(최소 단위, 양수). */
data class DiscountLineResponse(val type: String, val ref: String, val label: String, val amount: Long) {
    companion object {
        fun of(line: OrderDiscount) = DiscountLineResponse(line.kind, line.sourceRef, line.label, line.amount)
        fun of(draft: DiscountDraft) = DiscountLineResponse(draft.kind, draft.sourceRef, draft.label, draft.amount)
    }
}

/** `POST /quote` 의 응답 - 주문을 만들지 않고 본 금액. `settlement` 가 `FREE` · `EXEMPT` 면 결제창 없이 끝난다. */
data class QuoteResponse(val originalAmount: Long, val amount: Long, val currency: String, val discounts: List<DiscountLineResponse>, val settlement: String) {
    companion object {
        fun of(quote: OrderQuote) = QuoteResponse(quote.originalAmount, quote.amount, quote.currency, quote.discounts.map(DiscountLineResponse::of), quote.settlement.name)
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
    /** 운영자가 금액을 고쳐 승인했다면 처음 요청한 금액(그때 [amount] 는 승인한 금액), 아니면 `null`. */
    val requestedAmount: AmountResponse?,
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
            requestedAmount = refund.requestedAmount?.let { AmountResponse(it, refund.amount.normalizedCurrency) },
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
    /** 결제사가 결제창에 더 넘기는 공개 값(Paddle: `environment` · `priceId` · `customData`). 토스는 비어 있다. */
    val attributes: Map<String, String> = emptyMap(),
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
            attributes = checkout.attributes,
        )
    }
}

data class CreateOrderResponse(val order: OrderResponse, val checkout: CheckoutResponse?)

/**
 * [providers] 는 결제창을 열 수 있는 결제사 id — 화면이 나라 · 언어로 하나를 골라 주문 · 구독을 만들 때 `provider` 로 보낸다.
 * [pricePreviews] 는 결제사 id → 가격 미리보기 값(구매자 통화로 환산된 실제 결제 금액을 보이려는 결제사만).
 */
data class PaymentConfigResponse(
    val available: Boolean,
    val testMode: Boolean,
    val refundRequestsEnabled: Boolean,
    val providers: List<String> = emptyList(),
    val pricePreviews: Map<String, PricePreviewResponse> = emptyMap(),
    /** 결제사 id -> 결제창 안에서 바로 열 간편결제 코드(`NAVERPAY` …). 목록이 빈 결제사는 키가 없다. */
    val easyPays: Map<String, List<String>> = emptyMap(),
    /** 이 요청의 접속 나라(ISO-3166 두 글자) - 서버가 정했다. 모르면 null. */
    val country: String? = null,
    /** 이 요청의 나라로 서버가 정한 결제사. 주문을 만들 때 본문 `provider` 는 [choices] 안의 값만 받고 그 밖은 읽지 않는다(구독은 늘 이 값). 정할 수 없으면 null. */
    val provider: String? = null,
    /** 그 나라의 해지·환불 정책 요약. */
    val cancelPolicy: CancelPolicyResponse? = null,
    /** 개발용 나라 덮어쓰기가 켜져 있다(로컬만) - 켜져 있으면 요청의 `X-Dev-Country` 가 `country` 를 정한다. */
    val devCountrySwitch: Boolean = false,
    /** 구독을 시작할 때 구매자가 고를 수 있는 결제사 - [choices] 가운데 **빌링 능력**을 가진 것(카드 등록 · 간편결제 정기). 둘 이상일 때만 화면이 고르기를 그린다(`POST /subscriptions` 의 `provider`). 칸은 늘 나간다. */
    val subscriptionChoices: List<String> = emptyList(),
    /** 구매자가 고를 수 있는 결제사 - 이 나라를 자기 `supportedCountries` 에 적은 열린 결제사. 둘 이상일 때만 화면이 고르기를 그린다(주문을 만들 때 `provider` 로 보낸다). 하나 이하면 고르기가 없다. */
    val choices: List<String> = emptyList(),   // 칸은 늘 나간다(비어도 `[]`) - pricePreviews 와 같다
    /** 이 요청의 결제 통화(ISO-4217) - 나라 → 결제사를 정하는 라우팅이 같이 정한다. 정할 수 없으면 null(칸은 늘 나간다). */
    val currency: String? = null,
)

/** [key] 는 정책 이름(`korea` · `period-end-only` …) - 화면이 요약 문구를 고른다. 숫자는 한국 정책일 때만(설정값). */
data class CancelPolicyResponse(val key: String, val penaltyPercent: Int? = null, val withdrawalDays: Long? = null)

data class PricePreviewResponse(val clientToken: String, val environment: String, val prices: Map<String, String>)

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
    val easyPayBrand: String?,
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
    /** 서버에서 환불을 보낼 수 없는 이유 - `UNSUPPORTED` · `PROVIDER_OFF` · `MODE_MISMATCH`, 막힘이 없으면 `null`. 칸은 늘 온다. */
    val refundBlock: String?,
    val originalAmount: Long?,
    val discountAmount: Long,
    val discounts: List<DiscountLineResponse>,
    val settlement: String,
) {
    companion object {
        fun of(view: OperatorOrderView): AdminOrderResponse {
            val o = OrderResponse.of(view.order, view.summary.refundableAmount, view.summary.refundPending, view.summary.openRefundStatus?.name, view.discounts)
            return AdminOrderResponse(
                id = o.id, status = o.status, kind = o.kind, itemType = o.itemType, itemRef = o.itemRef, quantity = o.quantity, orderName = o.orderName, amount = o.amount,
                refundedAmount = o.refundedAmount, refundableAmount = o.refundableAmount, refundPending = o.refundPending, openRefundStatus = o.openRefundStatus,
                method = o.method, card = o.card, easyPayBrand = o.easyPayBrand, testMode = o.testMode, failureCode = o.failureCode, createdAt = o.createdAt, paidAt = o.paidAt, fulfilled = o.fulfilled,
                accountId = view.order.accountId,
                provider = view.order.provider,
                providerPaymentId = view.order.providerPaymentId,
                reviewReason = view.reviewReason,
                accountErasedAt = view.order.accountErasedAt,
                refundBlock = view.refundBlock?.name,
                originalAmount = o.originalAmount, discountAmount = o.discountAmount, discounts = o.discounts, settlement = o.settlement,
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
    val requestedAmount: AmountResponse?,
    /** 「구독도 해지」를 끄고 보낸 환불이면 `true`. 칸은 늘 온다. */
    val keepSubscription: Boolean,
    /** 그 주문의 종류(`SUBSCRIPTION_INITIAL` …) - 화면이 「구독도 해지」 선택을 보일지 정한다. */
    val orderKind: String?,
    /** 그 주문에 서버에서 환불을 보낼 수 없는 이유 - [AdminOrderResponse.refundBlock] 과 같다. */
    val refundBlock: String?,
) {
    companion object {
        fun of(refund: PaymentRefund, order: RefundOrderInfo? = null): AdminRefundResponse {
            val r = RefundResponse.of(refund)
            return AdminRefundResponse(
                id = r.id, orderId = r.orderId, status = r.status, amount = r.amount, reasonCode = r.reasonCode, origin = r.origin, requestedAt = r.requestedAt, completedAt = r.completedAt,
                closedBy = r.closedBy, requestedBy = refund.requestedBy, decidedBy = refund.decidedBy, failureCode = refund.failureCode, requestedAmount = r.requestedAmount,
                keepSubscription = refund.keepSubscription, orderKind = order?.kind?.name, refundBlock = order?.block?.name,
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
            refunds = detail.view.refunds.map { AdminRefundResponse.of(it, RefundOrderInfo(detail.view.order.kind, detail.view.refundBlock)) },
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

data class SubscriptionPlanResponse(val code: String, val name: String, val amount: AmountResponse, val period: String, val level: Int, val productCode: String, val regularAmount: Long?) {
    companion object {
        fun of(plan: dev.sumin.skeleton.payment.SubscriptionPlan, languages: List<String> = emptyList()) = SubscriptionPlanResponse(plan.code, languages.firstNotNullOfOrNull { plan.names[it] } ?: plan.name, plan.amount.toResponse(), plan.period, plan.level, plan.productCode, plan.regularAmount)
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
    /** 플랜 변경의 결제가 확인 중이면 바뀔 플랜 코드(`planCode` 는 아직 옛 플랜) — 아니면 null. */
    val pendingPlanCode: String?,
    val testMode: Boolean,
    val createdAt: Instant,
    /** 구매자 나라(ISO-3166 두 글자) - 해지·환불 정책을 고른 열쇠. 모르면 null. */
    val country: String?,
    /** 어느 결제사의 구독인가(`toss` · `paddle` …). */
    val provider: String,
    /** 결제사가 갱신을 직접 하는 구독이다 - 플랜 변경 · 중도 해지(환불)는 열리지 않고 해지는 기간 끝에만 된다. */
    val providerManaged: Boolean,
    /** 간편결제 정기 구독의 브랜드(`KAKAOPAY` · `NAVERPAY`) - 카드 끝 네 글자가 없는 구독의 「내 구독」 표시에 쓴다. 그 밖은 null. */
    val easyPayBrand: String? = null,
    /** 할인이 붙은 구독이면 그 내용(다음 결제액 · 남은 할인 횟수), 아니면 `null`. 칸은 늘 온다. */
    val discount: SubscriptionDiscountResponse? = null,
) {
    companion object {
        fun of(subscription: dev.sumin.skeleton.payment.PaymentSubscription, planName: String?, discount: dev.sumin.skeleton.payment.SubscriptionDiscountView? = null) = SubscriptionResponse(
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
            pendingPlanCode = subscription.pendingPlanCode,
            testMode = subscription.providerMode == PaymentMode.TEST,
            createdAt = subscription.createdAt,
            country = subscription.country,
            provider = subscription.provider,
            providerManaged = subscription.isProviderManaged,
            easyPayBrand = when (subscription.provider) {
                "kakaopay" -> "KAKAOPAY"
                "naverpay" -> "NAVERPAY"
                else -> null
            },
            discount = discount?.let(SubscriptionDiscountResponse::of),
        )
    }
}

/** 구독에 붙은 할인 - [stages] 는 시작 때 복사한 단계(쿠폰 정의를 고쳐도 그대로), [nextAmount] 는 다음 결제액(최소 단위, 구독 통화), [remainingPeriods] 는 남은 할인 횟수(영원이면 `null`). */
data class SubscriptionDiscountResponse(val label: String, val stages: List<DiscountStageResponse>, val periodsUsed: Int, val nextAmount: Long, val remainingPeriods: Int?) {
    companion object {
        fun of(view: dev.sumin.skeleton.payment.SubscriptionDiscountView) =
            SubscriptionDiscountResponse(view.label, view.stages.map { DiscountStageResponse(it.type.name, it.value, it.maxDiscount, it.periods) }, view.periodsUsed, view.nextAmount.amount, view.remainingPeriods)
    }
}

data class DiscountStageResponse(val type: String, val value: Long, val maxDiscount: Long?, val periods: Int?)

/** `GET /subscriptions/{id}/change-preview` — [charge] 는 지금 결제될 금액(0 이면 과금 없이 바뀐다), [amount] 는 새 플랜의 전체 가격, [restartsPeriod] 면 오늘부터 새 주기. */
data class PlanChangePreviewResponse(val planCode: String, val planName: String, val charge: AmountResponse, val amount: AmountResponse, val period: String, val restartsPeriod: Boolean) {
    companion object {
        fun of(quote: dev.sumin.skeleton.payment.PlanChangeQuote) =
            PlanChangePreviewResponse(quote.plan.code, quote.plan.name, quote.charge.toResponse(), quote.plan.amount.toResponse(), quote.plan.period, quote.restartsPeriod)
    }
}

/** 계산 내역 한 줄 - `PAID`(낸 돈, 양수) · `USED`(쓴 몫, 음수) · `PENALTY`(위약금, 음수). 합이 환불 금액이다. */
data class CancelLineResponse(val code: String, val amount: Long)

/** 중도 해지 미리보기 - [open] 이 false 면 이 구독은 기간 끝 해지만 된다(환불 0). [withdrawal] 은 7일 철회로 전액을 돌려주는 경우. */
data class CancelPreviewResponse(val open: Boolean, val refund: AmountResponse, val withdrawal: Boolean, val lines: List<CancelLineResponse>) {
    companion object {
        fun of(preview: dev.sumin.skeleton.payment.CancelPreview): CancelPreviewResponse {
            val quote = preview.quote
            return CancelPreviewResponse(quote.open, dev.sumin.skeleton.payment.PaymentAmount(quote.refund, preview.subscription.amount.currency).toResponse(), quote.withdrawal, quote.lines.map { CancelLineResponse(it.code.name, it.amount) })
        }
    }
}

/** 주문 하나의 환불 결과 - `status` 는 `SUCCEEDED` · `PENDING`(결제사 응답 대기) · `FAILED`(결제사 거절) · `NOT_SENT`(아직 못 보냄 - 서버가 다시 보낸다). */
data class CancelRefundResponse(val orderId: String, val amount: AmountResponse, val status: String)

data class CancelNowResponse(val subscription: SubscriptionResponse, val refund: AmountResponse, val withdrawal: Boolean, val lines: List<CancelLineResponse>, val refunds: List<CancelRefundResponse>) {
    companion object {
        fun of(result: dev.sumin.skeleton.payment.CancelNowResult, planName: String?): CancelNowResponse {
            val currency = result.subscription.amount.currency
            return CancelNowResponse(
                SubscriptionResponse.of(result.subscription, planName),
                PaymentAmount(result.quote.refund, currency).toResponse(),
                result.quote.withdrawal,
                result.quote.lines.map { CancelLineResponse(it.code.name, it.amount) },
                result.refunds.map { CancelRefundResponse(it.orderId, PaymentAmount(it.amount, currency).toResponse(), it.status.name) },
            )
        }
    }
}

/** 카드 등록 창을 여는 값. 프론트가 [kind] 로 갈라 연다. */
data class BillingAuthResponse(val provider: String, val kind: String, val testMode: Boolean, val clientKey: String?, val customerKey: String, val attributes: Map<String, String>) {
    companion object {
        fun of(setup: dev.sumin.skeleton.payment.BillingSetup) = BillingAuthResponse(setup.provider, setup.kind, setup.testMode, setup.clientKey, setup.customerKey, setup.attributes)
    }
}

data class StartSubscriptionResponse(val subscription: SubscriptionResponse, val billingAuth: BillingAuthResponse?)

/** `GET /subscriptions/me` — 지금 구독(없으면 `null`)과 최근 결제 다섯 건. */
data class MySubscriptionResponse(val subscription: SubscriptionResponse?, val orders: List<OrderResponse>)
