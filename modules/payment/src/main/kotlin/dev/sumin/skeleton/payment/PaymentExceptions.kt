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
