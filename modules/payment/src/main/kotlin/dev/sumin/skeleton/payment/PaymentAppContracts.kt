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
 * 운영자 경로를 쓸 수 있는가(HTTP 는 B7). 기본: platform 의 `OperatorAccess` 가 권한(`payment.read` · `payment.refund` · `payment.operate`)별로 판정한다.
 * 앱이 빈을 두면 갈아 끼운다 — [isOperator] 만 구현한 옛 정책은 [permits] 기본 구현 덕에 권한을 가르지 않고 모든 운영 경로를 한꺼번에 허락 · 거절한다.
 */
fun interface PaymentOperatorPolicy {
    fun isOperator(authentication: Authentication): Boolean

    /** [permission] 이 필요한 운영 경로를 쓸 수 있는가. 기본은 [isOperator] 그대로 */
    fun permits(authentication: Authentication, permission: String): Boolean = isOperator(authentication)
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
 * 구매자가 카드사에 이의를 제기해(차지백) 결제사가 돈을 가져갔다는 것, 그리고 분쟁에서 이겨 돌아왔다는 것을 밖에 알린다. **주문 id 말고는 아무 값도 싣지 않는다.**
 * `alert` 모듈이 있고 장부가 있으면 `OwnerAlerts.emit(PAYMENT_CHARGEBACK | PAYMENT_CHARGEBACK_REVERSED, key = 주문 id)`, 없으면 알리지 않는다(줄과 이력에는 남는다).
 */
interface PaymentChargebackAlert {
    fun chargeback(orderId: String) {}

    fun reversed(orderId: String) {}

    companion object {
        val NONE: PaymentChargebackAlert = object : PaymentChargebackAlert {}
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

/** 다른 주문 · 금액의 승인을 결제사 모듈이 어떻게 처리했나 - 취소했다 / 취소가 아직 끝나지 않았다(결제사가 곧 다시 처리한다고 한다) / 취소하지 못했거나 확인하지 못했다. */
enum class PaymentMismatchOutcome { CANCELLED, IN_PROGRESS, FAILED }

/**
 * 결제사가 **다른 주문 · 다른 금액의 결제를 이 주문의 승인으로 받아 버려**(승인 API 가 결제 번호만 받는 결제사) 결제 모듈이 그 승인을 곧바로 취소했거나 취소하지 못했다는 것을 밖에 알린다.
 * [PaymentMismatchOutcome.FAILED] 면 돈이 아직 결제사에 걸려 있다 - 사람이 풀어야 한다. [PaymentMismatchOutcome.IN_PROGRESS] 는 결제사가 취소를 곧 다시 처리한다고 한 때라 알아 두면 된다. **결제사 id 와 주문 id 말고는 아무 값도 싣지 않는다.**
 * `alert` 모듈이 있고 장부가 있으면 `OwnerAlerts.emit(PAYMENT_MISMATCH_CANCELLED | PAYMENT_MISMATCH_CANCEL_PENDING | PAYMENT_MISMATCH_CANCEL_FAILED, key = 주문 id)`, 없으면 알리지 않는다. 결제사 모듈이 부른다.
 */
fun interface PaymentProviderMismatchAlert {
    fun applied(providerId: String, orderId: String, outcome: PaymentMismatchOutcome)

    companion object {
        val NONE: PaymentProviderMismatchAlert = PaymentProviderMismatchAlert { _, _, _ -> }
    }
}

/** 구독 플랜 하나. [period] 는 ISO-8601(`P1M` · `PT3M`), [level] 은 높을수록 윗등급이다(2차의 위로만 바꾸기가 쓴다). */
data class SubscriptionPlan(
    val code: String,
    val name: String,
    val amount: PaymentAmount,
    val period: String,
    val level: Int,
)

/**
 * 무엇을 얼마에 구독하는가 — **구독 금액의 유일한 출처**([PaymentCatalog] 와 같은 원칙). 앱이 구현한다.
 * `skeleton.payment.subscription.enabled=true` 인데 이 빈이 없으면 기동 실패. 구독 줄에는 만들 때의 코드 · 금액 · 주기가 박힌다.
 */
interface SubscriptionPlans {
    fun list(): List<SubscriptionPlan>

    fun find(code: String): SubscriptionPlan? = list().firstOrNull { it.code == code }
}

/**
 * 이 계정의 구독을 **갱신해도 되는가**(정지 · 삭제 유예 계정은 해지할 수 없으니 과금하지 않는다). 갱신 시각마다 락 밖에서 불린다 — 짧게 끝낸다.
 * false 면 구독은 그 기간 끝에 `RENEWAL_BLOCKED` 로 끝난다. 기본은 항상 허용([ALLOW_ALL]) — `payment` 는 `account` 를 모른다.
 */
fun interface SubscriptionRenewalGuard {
    fun allows(accountId: String): Boolean

    companion object {
        val ALLOW_ALL: SubscriptionRenewalGuard = SubscriptionRenewalGuard { true }
    }
}
