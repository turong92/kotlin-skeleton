package dev.sumin.skeleton.payment

import org.springframework.security.core.Authentication

/**
 * 무엇을 얼마에 파는가 — **금액의 유일한 출처**(스펙 §4.2). 앱이 구현하고, 주문 줄에 박힌 값만 결제사로 간다.
 * 살 수 없으면 [PaymentItemNotPurchasableException] 을 던진다. 이 빈이 없으면 주문을 만드는 길이 열리지 않는다([PaymentUnavailableException]).
 */
fun interface PaymentCatalog {
    fun quote(request: PurchaseRequest): PurchaseQuote
}

data class PurchaseRequest(
    val accountId: String,
    val itemType: String,
    val itemRef: String,
    val quantity: Int,
    val country: String?,
)

/** [orderName] 은 결제창에 보이는 이름이다 — 개인정보를 넣지 않는다(최대 100자). */
data class PurchaseQuote(
    val amount: PaymentAmount,
    val orderName: String,
)

/**
 * 결제 · 환불이 확정된 뒤 불린다. **주문 줄 락 안에서 한 번**이라 같은 DB 를 쓰는 지급은 정확히 한 번이다 — 그래도 드물게 다시 불릴 수 있으니 멱등하게 쓴다.
 * 던지면 그 트랜잭션이 되돌려지고(`fulfilled_at` 은 빈 채) 「결제됨」은 그대로이며 지급 작업이 재시도한다.
 *
 * **짧게 끝낸다.** 두 훅은 **주문 줄 락 안에서** 돈다 — 그동안 그 주문의 웹훅 수신 · 승인 · 환불이 기다린다(PostgreSQL 은 락 대기에 시간 제한이 없다).
 * 느린 일(메일 · 외부 호출)은 훅에서 직접 하지 말고 큐로 넘긴다(예: 알림은 `NotificationPublisher`, 외부 시스템 호출은 작업으로).
 *
 * **지급한 적 없는 주문에도 [onRefunded] 가 온다** — 지급 전에 전액 환불되면 [onPaid] 는 건너뛰고 [onRefunded] 만 불린다. 지급했는지는 `order.fulfilledAt == null` 로 가를 수 있다
 * (앱의 회수는 "지급했으면 되돌린다"로 멱등하게 쓴다). 일부만 환불된 미지급 주문은 먼저 [onPaid] 가 불린 뒤 [onRefunded] 가 온다 — **[onRefunded] 는 [onPaid] 보다 앞서지 않는다.**
 */
interface PaymentOrderListener {
    fun onPaid(order: PaymentOrder) {}

    fun onRefunded(order: PaymentOrder, refund: PaymentRefund) {}

    companion object {
        /** 아무것도 하지 않는다 — 리스너 빈이 없는 조립의 기본. */
        val NONE: PaymentOrderListener = object : PaymentOrderListener {}
    }
}

/**
 * 운영자 경로를 쓸 수 있는가(HTTP 는 B7). 기본: 인증 토큰의 역할. 앱이 빈을 두면 갈아 끼운다
 * (토큰이 낡았을 수 있다 — `account` 를 쓰는 앱은 매 호출 저장소의 역할을 다시 읽는다).
 */
fun interface PaymentOperatorPolicy {
    fun isOperator(authentication: Authentication): Boolean
}

/**
 * 사람이 봐야 하는 일이 줄에 찍힌 순간(`review_reason`)을 밖에 알린다. **주문 id 말고는 아무 값도 싣지 않는다.**
 * `alert` 모듈이 있으면 `OwnerAlerts.emit(PAYMENT_REVIEW_NEEDED, key = 주문 id)` 가 되고(`PaymentAlertAutoConfiguration`), 없으면 줄 · 로그에만 남는다.
 */
fun interface PaymentReviewNotifier {
    fun flagged(orderId: String, reason: PaymentReviewReason)

    companion object {
        val NONE: PaymentReviewNotifier = PaymentReviewNotifier { _, _ -> }
    }
}

/**
 * 결제사가 **우리 키를 받아들이지 않았다**(폐기 · 오타 · 권한 없음 — 인증 계열 거절)는 것을 밖에 알린다. 그 거절은 확정 실패로 분류되어 고객 쪽에는 「거절」로만 보이므로
 * (키가 폐기된 배포는 모든 결제가 거절된다) 사람이 알아야 한다. **결제사 id 말고는 아무 값도 싣지 않는다.**
 * `alert` 모듈이 있고 장부가 있으면 `OwnerAlerts.emit(PAYMENT_PROVIDER_CREDENTIALS_REJECTED, key = 결제사 id)`(1시간에 한 번으로 접는다)가 되고, 없으면 알리지 않는다. 결제사 모듈이 부른다.
 */
fun interface PaymentProviderCredentialAlert {
    fun rejected(providerId: String)

    companion object {
        val NONE: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert { }
    }
}
