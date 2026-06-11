package dev.sumin.skeleton.redis.lock

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.redis-lock")
data class RedisLockProperties(
    val enabled: Boolean = true,
    val startupCheck: StartupCheck = StartupCheck(),
    val retry: Retry = Retry(),
    val redisson: Redisson = Redisson(),
) {
    data class StartupCheck(
        val enabled: Boolean = true,
        val key: String = "__redis_lock_startup_check",
    )

    data class Retry(
        val attempts: Int = 3,
        val backoff: Duration = Duration.ofMillis(200),
    )

    data class Redisson(
        val retryAttempts: Int = 3,
        val retryInterval: Duration = Duration.ofMillis(1500),
        val timeout: Duration = Duration.ofSeconds(3),
    )
}
