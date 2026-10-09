package dev.sumin.skeleton.payment

import java.time.Instant

/** 주문 목록의 커서 — 만든 시각(저장소의 정밀도 그대로) + id. 최신순 목록에서 이 줄 **다음**부터 읽는다. */
data class OrderCursor(val createdAt: Instant, val id: String)

/** 환불 목록의 커서 — 요청한 시각 + id. 최신순 목록에서 이 줄 **다음**부터 읽는다. */
data class RefundCursor(val requestedAt: Instant, val id: String)

/**
 * 운영자의 주문 목록이 훑는 범위. 「확인 필요」(`review=true`)는 서로 다른 인덱스를 타는 여섯 갈래의 합이라 갈래마다 한 번씩 묻고 서비스가 합친다
 * (한 문장에 `or` 로 묶으면 어느 인덱스도 못 탄다).
 */
enum class OperatorOrderScope(
    /** `threshold` 가 있어야 하는 범위. */
    val needsThreshold: Boolean = true,
) {
    /** 전부(필터만 적용). 만든 시각 최신순 — `idx_payment_orders_created` · `idx_payment_orders_status`. */
    ALL(needsThreshold = false),

    /** `review_reason` 이 찍힌 줄 — `idx_payment_orders_review`. */
    REVIEW_REASON(needsThreshold = false),

    /** `CONFIRMING` 인 채 `threshold` 보다 오래된 줄(`confirm_started_at < threshold`) — 마지막 안전망. */
    STALE_CONFIRMING,

    /** 돈은 받았는데(`PAID` · `PARTIALLY_REFUNDED`) 지급 시각이 비고 `paid_at < threshold` 인 줄 — `idx_payment_orders_unfulfilled`. */
    UNFULFILLED,

    /** 오래(`sent_at < threshold`) `PENDING` 인 환불 줄을 가진 주문 — 필터 · 커서 · 개수 제한이 주문 쪽에서 걸린다(환불 줄을 읽은 뒤 메모리에서 거르지 않는다). */
    STALE_REFUND,

    /** 환불은 `SUCCEEDED` 인데 회수(`applied_at`)가 비고 `completed_at < threshold`(또는 비어 있음)인 줄을 가진 주문. */
    UNAPPLIED_REFUND,
}

/** 운영자 목록의 공통 필터. [hideErased] 면 탈퇴한 계정의 줄(`account_erased_at`)을 뺀다. */
data class OperatorOrderFilter(
    val status: PaymentOrderStatus? = null,
    val accountId: String? = null,
    val hideErased: Boolean = false,
)
