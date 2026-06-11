package dev.sumin.skeleton.idempotency

data class IdempotencyContext(
    val scopedKey: String,
)

object IdempotencyAttributes {
    const val CONTEXT = "dev.sumin.skeleton.idempotency.context"
    const val CACHED_BODY = "dev.sumin.skeleton.idempotency.cachedBody"
}
