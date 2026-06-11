package dev.sumin.skeleton.redis.cache

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.redis-cache")
data class RedisCacheProperties(
    val enabled: Boolean = true,
    val defaultTtl: Duration = Duration.ofMinutes(10),
    val failurePolicy: FailurePolicy = FailurePolicy.FAIL_OPEN,
    val noOpFallback: NoOpFallback = NoOpFallback(),
    val caches: Map<String, CacheSpec> = emptyMap(),
) {
    enum class FailurePolicy {
        FAIL_OPEN,
        FAIL_CLOSED,
    }

    data class NoOpFallback(
        val enabled: Boolean = false,
    )

    data class CacheSpec(
        val ttl: Duration? = null,
    )
}
