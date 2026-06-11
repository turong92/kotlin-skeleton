package dev.sumin.skeleton.idempotency

object IdempotencyHeaders {
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val IDEMPOTENCY_REPLAYED = "X-Idempotency-Replayed"
}
