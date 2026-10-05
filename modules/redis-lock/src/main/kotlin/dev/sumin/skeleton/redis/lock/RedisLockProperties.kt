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
    /** 기동 때 Redis 를 찔러 본다. 꺼 두면 연결은 첫 락 사용 때 일어난다(Redis 없이도 앱이 뜬다). 운영에서는 켠다. */
    data class StartupCheck(
        val enabled: Boolean = false,
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
