package dev.sumin.skeleton.redis.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.redis-rate-limit")
data class RedisRateLimitProperties(
    val enabled: Boolean = true,
    val failurePolicy: FailurePolicy = FailurePolicy.FAIL_OPEN,
    val keyNamespace: String = "default",
) {
    enum class FailurePolicy {
        FAIL_OPEN,
        FAIL_CLOSED,
    }
}
