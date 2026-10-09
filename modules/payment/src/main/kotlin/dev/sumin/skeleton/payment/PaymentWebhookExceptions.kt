package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.ApplicationException

/**
 * 웹훅 주소가 맞지 않는다 — 모르는 결제사 · 웹훅 능력이 없는 결제사 · 꺼진 결제사 · 비밀 조각이 없거나 틀림. **어느 쪽인지 구별하지 않는다**(같은 메시지 · 같은 404):
 * 주소의 어느 조각이 맞았는지 알려 주면 비밀 조각을 한 조각씩 짐작할 수 있다.
 */
class PaymentWebhookNotFoundException : ApplicationException(
    message = "The payment webhook address was not found.",
    errorCode = PaymentErrorCode.WEBHOOK_NOT_FOUND,
)

/** 본문이 `webhook.max-body-bytes` 를 넘는다 — 주소가 맞은 요청에서만 말한다. 본문은 더 읽지 않았다. */
class PaymentWebhookTooLargeException : ApplicationException(
    message = "The payment webhook body is too large.",
    errorCode = PaymentErrorCode.WEBHOOK_TOO_LARGE,
)

/** 운영자가 다시 처리하려는 수신함 줄이 없다 — B7 의 컨트롤러가 `404 PAYMENT.WEBHOOK_EVENT_NOT_FOUND` 로 옮긴다. */
class PaymentWebhookEventNotFoundException : ApplicationException(
    message = "The payment webhook event was not found.",
    errorCode = PaymentErrorCode.WEBHOOK_EVENT_NOT_FOUND,
)
