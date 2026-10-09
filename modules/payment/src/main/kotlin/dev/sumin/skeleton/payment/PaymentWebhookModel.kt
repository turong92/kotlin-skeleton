package dev.sumin.skeleton.payment

import java.time.Instant

/** 수신함 목록의 커서 — 최신순으로 훑을 때 "이 줄 다음부터"(이 줄은 빠진다). 받은 시각, 같으면 id 가 큰 쪽이 먼저다. */
data class WebhookEventCursor(val receivedAt: Instant, val id: Long)

/**
 * [PaymentLedgerTx.recordWebhookDelivery] 의 답. [event] 는 이번 전달을 센 **뒤의** 줄이고, [first] 는 이 전달이 줄을 만들었는가
 * (같은 이벤트가 동시에 여럿 도착해도 정확히 하나만 true). 이 트랜잭션이 그 줄을 잠근 채다.
 */
data class RecordedWebhookDelivery(val event: PaymentWebhookEvent, val first: Boolean)

/** 수신함 목록 한 쪽 — [next] 가 null 이 아니면 그 커서로 다음 쪽을 부른다. */
data class WebhookEventPage(val values: List<PaymentWebhookEvent>, val next: WebhookEventCursor?)

/**
 * 수신 한 번의 결과 — 결제사에는 모두 `200` 이라 구별하지 않는다(위조자가 아는 주문인지 알아내지 못하게). 시험과 로그를 위한 구분이다.
 * - [STORED] 새 줄을 넣고 처리 작업을 넣었다
 * - [DUPLICATE] 이미 있는 이벤트 — 줄이 끝났거나(`PROCESSED` · `IGNORED`) 작업이 기다리는 중(`RECEIVED`)이라 할 일이 없다 (`deliveries` 만 올랐다)
 * - [REQUEUED] 이미 있는 이벤트인데 줄이 `FAILED` · `DEAD` 라 대기 · 시도 예산을 건너뛰고 새 작업을 바로 넣었다 (줄은 `RECEIVED` 로 돌아갔다). 그 줄의 총 전달 수(첫 전달 포함)가 `webhook.redelivery-requeue-limit` 을 넘었으면 [DUPLICATE]
 * - [UNKNOWN_ORDER] 우리가 만든 주문이 아니다 — 저장하지 않았다
 * - [UNSUPPORTED] 1단계가 다루지 않는 이벤트(결제 이벤트가 아니다) — 저장하지 않았다
 * - [LIMITED] 새 이벤트인데 그 주문이 창 안에서 `webhook.per-order-limit` 만큼 이미 받았다 — 이 이벤트는 저장하지 않았고 지금 도는 작업 · 결제사 조회도 없다. 신호는 버려지지 않는다: 주문당 창마다 표식 줄([WebhookMarkers]) 하나와 창 끝에 도는 처리 작업 하나가 걸린다
 */
enum class WebhookReceipt { STORED, DUPLICATE, REQUEUED, UNKNOWN_ORDER, UNSUPPORTED, LIMITED }

/**
 * 상한에 걸린 신호의 **표식 줄**(스펙 §6.5 · §15) — 주문당 창마다 하나. 진짜 배달이 아니라 우리가 만든 줄이라 상한 계산에 세지 않고(`resource_type` 이 `PAYMENT` 가 아니다),
 * 결제사 본문이 만들 수도 없다(본문의 `resource_type` 은 `PAYMENT` 만 저장된다). `event_key` 의 접두사 `limited:` 는 진짜 이벤트 키로 받지 않는다 — 표식과 키가 겹치지 않게.
 */
object WebhookMarkers {
    const val RESOURCE_TYPE = "LIMIT_MARKER"
    const val EVENT_TYPE = "LIMIT_REACHED"
    const val KEY_PREFIX = "limited:"

    /** 창 번호 = 받은 시각(epoch ms) / 창 길이(ms) 의 몫. */
    fun key(orderId: String, window: Long): String = "$KEY_PREFIX$orderId:$window"
}

/** 수신함 줄의 `last_error` · 이력에 쓰는 **우리 코드** — 예외 메시지 · 본문 조각은 어디에도 쓰지 않는다. */
object WebhookCodes {
    /** 결제사에 물을 수 없었다(타임아웃 · 5xx · 해석 못 함) — 작업이 다시 한다. */
    const val LOOKUP_UNAVAILABLE = "LOOKUP_UNAVAILABLE"

    /** 조회 말고 맞추는 도중 실패했다(저장소 오류 …) — 되돌려졌고 작업이 다시 한다. */
    const val PROCESSING_FAILED = "PROCESSING_FAILED"

    /** 결제사 빈이 지금 없다 — 설정으로 고쳐지는 상태라 작업이 다시 한다. */
    const val PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"

    /** 그 결제사는 조회 능력이 없다 — 다시 해 봐야 소용없다. */
    const val LOOKUP_UNSUPPORTED = "LOOKUP_UNSUPPORTED"

    /** 주문을 만들 때의 키 모드와 지금 키의 모드가 다르다 — 다시 해 봐야 소용없다(실결제 웹훅이 개발 주소에도 등록된 설정 오류를 드러낸다). */
    const val MODE_MISMATCH = "MODE_MISMATCH"

    /** 결제사가 그 결제를 모른다 — `IGNORED`. */
    const val NO_PAYMENT = "NO_PAYMENT"

    /** 줄이 가리키는 주문이 없다 — `IGNORED`. */
    const val UNKNOWN_ORDER = "UNKNOWN_ORDER"

    /** 죽은 작업의 고리가 찍은 기본 사유(앞선 시도의 사유가 없을 때). */
    const val RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED"
}
