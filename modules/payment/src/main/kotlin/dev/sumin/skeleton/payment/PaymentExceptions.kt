package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.http.ExternalHttpException
import java.net.URI

/**
 * 응답의 `detail` 은 고정 문장이다 — 결제사 id · 내부 인터페이스 이름을 싣지 않는다. 로그가 읽는 `message` 에는 어느 결제사인지 남긴다.
 * [providerId] 는 클라이언트가 보낸 값일 수 있어 로그 줄을 깨지 못하게 다듬는다(출력 가능한 ASCII 만, 64자까지).
 */
class PaymentProviderNotFoundException(
    val providerId: String,
) : ApplicationException(
    message = "Payment provider not available: ${logSafe(providerId)}",
    errorCode = PaymentErrorCode.PROVIDER_NOT_FOUND,
    detail = "The requested payment provider is not available.",
)

/** 응답의 `detail` 은 고정 문장이다. 로그의 `message` 에는 결제사와 능력이 남는다. */
class PaymentCapabilityUnsupportedException(
    val providerId: String,
    val capability: String,
) : ApplicationException(
    message = "Payment provider ${logSafe(providerId)} does not support ${logSafe(capability)}",
    errorCode = PaymentErrorCode.CAPABILITY_UNSUPPORTED,
    detail = "The payment provider does not support this operation.",
)

private fun logSafe(value: String): String = value.take(64).replace(Regex("[^\\x21-\\x7E]"), "?")

class PaymentRoutingException(
    message: String,
) : ApplicationException(
    message = message,
    errorCode = PaymentErrorCode.ROUTING_FAILED,
)

/**
 * 웹훅이 진짜가 아니거나 읽을 수 없어 거절됐다([PaymentWebhookGateway.parse]). 메시지는 고정 문장이다 — 본문 · 헤더 · 결제사 id 를 싣지 않는다.
 * 받는 쪽은 이 타입을 "거절"로 읽고, 그 밖의 예외는 "처리 실패"로 읽을 수 있다.
 */
class PaymentWebhookRejectedException : ApplicationException(
    message = "The payment webhook was rejected.",
    errorCode = PaymentErrorCode.WEBHOOK_REJECTED,
)

/** 남의 주문 · 없는 주문 — 둘을 구별하지 않는다(주문 id 가 맞는지 알려 주지 않는다). */
class PaymentOrderNotFoundException : ApplicationException(
    message = "The payment order was not found.",
    errorCode = PaymentErrorCode.ORDER_NOT_FOUND,
)

/** [PaymentCatalog] 가 던진다: 이 상품은 지금 살 수 없다(목록에 없는 상품 · 수량 · 품절 …). */
class PaymentItemNotPurchasableException(
    message: String = "This item cannot be purchased.",
) : ApplicationException(
    message = message,
    errorCode = PaymentErrorCode.ITEM_NOT_PURCHASABLE,
)

/** 같은 `Idempotency-Key` 가 다른 요청에 쓰였다. */
class PaymentIdempotencyConflictException : ApplicationException(
    message = "The idempotency key was already used for a different request.",
    errorCode = PaymentErrorCode.IDEMPOTENCY_CONFLICT,
)

/** 요청의 금액이 주문의 금액과 다르다 — 결제사를 부르지 않았다. */
class PaymentAmountMismatchException : ApplicationException(
    message = "The amount does not match the order.",
    errorCode = PaymentErrorCode.AMOUNT_MISMATCH,
)

/** 지금 상태에서는 결제할 수 없는 주문(이미 끝났다 · 시간이 지났다 · 다른 결제가 붙어 있다). */
class PaymentOrderNotPayableException : ApplicationException(
    message = "The payment order cannot be paid in its current state.",
    errorCode = PaymentErrorCode.ORDER_NOT_PAYABLE,
)

/** 주문을 만들 때의 키 모드(테스트 · 실결제)와 지금 끼운 키의 모드가 다르다. */
class PaymentModeMismatchException : ApplicationException(
    message = "The order was created with a different payment mode.",
    errorCode = PaymentErrorCode.MODE_MISMATCH,
)

/** 결제사가 거절했다(확정) — 주문은 `FAILED` 로 닫혔다. [providerCode] 는 결제사의 거절 코드(식별자 모양만). */
class PaymentDeclinedException(
    val providerCode: String?,
    /** 응답의 `data.category` — 결제사 모듈이 코드에서 옮긴 분류([PaymentConfirmGateway.confirmDeclineCategory]). */
    val category: ConfirmDeclineCategory = ConfirmDeclineCategory.OTHER,
) : ApplicationException(
    message = "The payment was declined.",
    errorCode = PaymentErrorCode.DECLINED,
    data = mapOf("providerCode" to providerCode, "category" to category.name),
)

/** 결제를 받을 수 없다 — 상품 카탈로그가 없거나 결제창을 여는 결제사가 없다. */
class PaymentUnavailableException : ApplicationException(
    message = "Payments are not available right now.",
    errorCode = PaymentErrorCode.UNAVAILABLE,
)

/** 운영자의 「결제사와 맞추기」: 결제사에 물었더니 그 결제를 모른다(조회가 null). 장부는 바꾸지 않았다. */
class PaymentProviderPaymentNotFoundException : ApplicationException(
    message = "The payment provider does not know this payment.",
    errorCode = PaymentErrorCode.PROVIDER_PAYMENT_NOT_FOUND,
)

data class PaymentProviderError(
    val provider: String,
    val code: String? = null,
    val message: String? = null,
    val trace: ProviderTrace,
    val upstreamStatus: Int,
    /** 일시 오류일 수 있어 다시 시도해 볼 만한가(429 · 5xx 등). 확정/모름은 [definitive] 가 정하고, 이 값은 모순(확정인데 다시 해 볼 만하다)일 때 모름으로 읽는 안전장치로만 본다. */
    val retryable: Boolean,
    val rawBody: String? = null,
    /**
     * **결제사가 "돈이 움직이지 않았다"고 말한 거절**이면 true. 기본값 false = 모름.
     *
     * 결제사 모듈의 의무: 결제사 문서가 "이 코드면 아무 일도 일어나지 않았다"고 보증하는 코드에만 true 를 단다(허용 목록).
     * 이미 처리됨(`ALREADY_*`) · 처리 중(멱등 키 충돌) · 일시 오류 · 카드사 응답 오류처럼 **앞선 요청이 적용됐을 수 있는 코드**와
     * 문서에서 확신하지 못한 코드는 false 로 둔다. 장부는 이 값이 true 인 4xx 만 `FAILED` 로 확정하고 나머지는 결제사에 조회한다
     * ([PaymentFailures.outcomeOf]). 표시를 빠뜨리면 모름이 되므로 안전한 쪽으로 틀린다.
     */
    val definitive: Boolean = false,
    /**
     * 확정 거절([definitive])인데 **결제사에 걸렸던 돈을 결제사 모듈이 이미 되돌렸다**(승인 API 가 결제 번호만 받아 다른 주문의 결제가 이 주문의 승인으로 받아졌고 모듈이 곧바로 전액 취소했다).
     * 장부가 그 사이 조회로 찍은 「결제사가 받았다」 표시(`AMOUNT_MISMATCH` · `ORDER_MISMATCH`)를 주문을 닫을 때 비운다 - 돈이 이미 돌아와 사람이 볼 일이 없다. 기본 false.
     */
    val moneyReturned: Boolean = false,
    /**
     * 결제사가 준 거절 이유 글을 [PaymentFailures.safeReason] 으로 가려 둔 것 - **로그 한 줄([PaymentFailures.describe])에만** 쓴다. 예외 메시지 · 응답 `data` · 이력에는 싣지 않는다.
     * 결제사 모듈이 이유가 사람 개인정보와 무관한 오류에만 채운다(카카오페이: 공통 오류 -400 · -401 · -403).
     */
    val reason: String? = null,
)

class PaymentProviderException(
    val providerError: PaymentProviderError,
    clientName: String,
    method: String,
    uri: URI,
    cause: Throwable? = null,
) : ExternalHttpException(
    message = providerError.message
        ?: providerError.code
        ?: "Payment provider '${providerError.provider}' returned HTTP ${providerError.upstreamStatus}",
    clientName = clientName,
    method = method,
    uri = uri,
    upstreamStatus = providerError.upstreamStatus,
    retryable = providerError.retryable,
    errorCode = PaymentErrorCode.PROVIDER_ERROR,
    // 결제사가 보낸 문장은 detail 에 싣지 않는다(고정 문장) — 원문은 로그의 message 와 운영자 응답의 data 에만 있다
    detail = "The payment provider returned an error.",
    data = providerError.toSafeData(),
    cause = cause,
)

private fun PaymentProviderError.toSafeData(): Map<String, Any?> =
    mapOf(
        "provider" to provider,
        "providerCode" to code,
        "providerMessage" to message,
        "providerTrace" to trace,
        "upstreamStatus" to upstreamStatus,
        "retryable" to retryable,
    )

/** 남의 구독 · 없는 구독 — 둘을 구별하지 않는다. */
class PaymentSubscriptionNotFoundException : ApplicationException(
    message = "The subscription was not found.",
    errorCode = PaymentErrorCode.SUBSCRIPTION_NOT_FOUND,
)

/** 이미 살아 있는 구독이 있다(계정당 하나) — 새 구독을 시작할 수 없다. */
class PaymentAlreadySubscribedException : ApplicationException(
    message = "This account already has a subscription.",
    errorCode = PaymentErrorCode.ALREADY_SUBSCRIBED,
)

/** 이 구독으로는 카드 등록 · 첫 결제를 할 수 없다(이미 끝났다). */
class PaymentSubscriptionNotActivatableException : ApplicationException(
    message = "This subscription can no longer be started.",
    errorCode = PaymentErrorCode.SUBSCRIPTION_NOT_ACTIVATABLE,
)

/** 아직 시작하지 않은 구독은 해지할 수 없다. */
class PaymentSubscriptionNotCancelableException : ApplicationException(
    message = "This subscription cannot be canceled in its current state.",
    errorCode = PaymentErrorCode.SUBSCRIPTION_NOT_CANCELABLE,
)

/** 중도 해지를 할 수 없다 — 이용 중이 아니다 · 이 나라의 정책이 중도 해지를 닫았다. */
class PaymentSubscriptionCancelNotAllowedException : ApplicationException(
    message = "This subscription cannot be canceled now with a refund.",
    errorCode = PaymentErrorCode.SUBSCRIPTION_CANCEL_NOT_ALLOWED,
)

/** 갱신 · 변경 결제가 오가는 중이라 지금 끝낼 수 없다 — 결제가 끝난 뒤 다시. */
class PaymentSubscriptionChargeInProgressException : ApplicationException(
    message = "A payment for this subscription is still in progress. Try again in a moment.",
    errorCode = PaymentErrorCode.SUBSCRIPTION_CHARGE_IN_PROGRESS,
)

/** 플랜 변경을 할 수 없다 — 꺼져 있다 · 이용 중이 아니다 · 위 등급이 아니다 · 주기 · 통화가 다르다 · 과금 중인 주문이 있다. */
class PaymentPlanChangeNotAllowedException : ApplicationException(
    message = "The plan cannot be changed now.",
    errorCode = PaymentErrorCode.PLAN_CHANGE_NOT_ALLOWED,
)

/** 서버가 다시 계산한 변경 금액이 호출자가 본 금액보다 크다 — 미리보기부터 다시 한다. */
class PaymentPlanChangeChargeChangedException : ApplicationException(
    message = "The charge for the plan change is higher than the one shown.",
    errorCode = PaymentErrorCode.PLAN_CHANGE_CHARGE_CHANGED,
)
