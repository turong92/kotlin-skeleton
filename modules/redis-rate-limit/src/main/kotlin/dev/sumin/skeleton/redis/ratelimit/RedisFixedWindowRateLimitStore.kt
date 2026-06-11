package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.common.web.RateLimitDecision
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import java.time.Instant
import org.slf4j.LoggerFactory

class RedisFixedWindowRateLimitStore(
    private val counter: RedisFixedWindowCounter,
    private val redisKeyPrefixer: RedisKeyPrefixer,
    private val properties: RedisRateLimitProperties,
) : RateLimitStore {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun consume(
        key: String,
        capacity: Int,
        windowMillis: Long,
        now: Instant,
    ): RateLimitDecision {
        val effectiveWindowMillis = windowMillis.coerceAtLeast(1)
        val currentMillis = now.toEpochMilli()
        val windowStart = (currentMillis / effectiveWindowMillis) * effectiveWindowMillis
        val resetAt = Instant.ofEpochMilli(windowStart + effectiveWindowMillis)
        val redisKey = redisKeyPrefixer.key(
            "rate-limit",
            properties.keyNamespace,
            key,
            windowStart.toString(),
        )

        return try {
            val count = counter.increment(redisKey, effectiveWindowMillis)
            RateLimitDecision(
                allowed = count <= capacity,
                limit = capacity,
                remaining = (capacity - count).coerceAtLeast(0).toInt(),
                resetAt = resetAt,
            )
        } catch (exception: RuntimeException) {
            log.warn("Redis rate limit failure for key={}", redisKey, exception)
            failureDecision(capacity, resetAt)
        }
    }

    private fun failureDecision(
        capacity: Int,
        resetAt: Instant,
    ): RateLimitDecision =
        when (properties.failurePolicy) {
            RedisRateLimitProperties.FailurePolicy.FAIL_OPEN -> RateLimitDecision(
                allowed = true,
                limit = capacity,
                remaining = capacity.coerceAtLeast(0),
                resetAt = resetAt,
            )

            RedisRateLimitProperties.FailurePolicy.FAIL_CLOSED -> RateLimitDecision(
                allowed = false,
                limit = capacity,
                remaining = 0,
                resetAt = resetAt,
            )
        }
}
