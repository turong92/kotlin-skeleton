package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.payment.PaymentProperties

/**
 * 웹훅 경로의 요청 수 한도 — 플랫폼의 [RateLimitStore] 위에(`redis-rate-limit` 이 저장소를 내면 Redis, 아니면 이 인스턴스의 메모리), 호출자 IP(`ClientIps….limitKey`)마다.
 * **주소를 확인하기 전에** 센다: 비밀 조각을 짐작하는 쪽이 먼저 막힌다. `skeleton.web.rate-limit`(전 경로 한도)와 따로 돈다.
 */
class PaymentWebhookRateLimiter(
    private val store: () -> RateLimitStore,
    private val time: TimeProvider,
    private val limit: PaymentProperties.Webhook.RateLimit,
) {
    data class Allowance(val allowed: Boolean, val retryAfterSeconds: Long)

    fun acquire(clientKey: String): Allowance {
        val now = time.now()
        val decision = store().consume("payment-webhook:$clientKey", limit.capacity, limit.window.toMillis(), now)
        return Allowance(decision.allowed, (decision.resetAt.epochSecond - now.epochSecond).coerceAtLeast(1))
    }
}
