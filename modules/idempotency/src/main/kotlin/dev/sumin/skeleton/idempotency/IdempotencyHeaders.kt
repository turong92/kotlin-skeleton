package dev.sumin.skeleton.idempotency

object IdempotencyHeaders {
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val IDEMPOTENCY_REPLAYED = "X-Idempotency-Replayed"

    /** OpenAPI 의 `maxLength` 와 같다 - 넘으면 400 (저장소 칸 길이도 이 값 기준) */
    const val MAX_KEY_LENGTH = 255
}
