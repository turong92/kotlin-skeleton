package dev.sumin.skeleton.jobqueue.jdbc

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   job-queue:
 *     enabled: true
 *     poll-interval: 2s
 *     batch-size: 10
 *     max-attempts: 10
 *     backoff:
 *       initial: 30s
 *       multiplier: 2.0
 *       max: 1h
 *     stale-lock-timeout: 10m   # RUNNING 인 채로 이 시간이 지나면 워커가 죽은 것으로 보고 PENDING 으로 되돌린다
 * ```
 */
@ConfigurationProperties("skeleton.job-queue")
data class JobQueueProperties(
    val enabled: Boolean = true,
    /** false 면 enqueue 만 되고 워커는 돌지 않는다 (테스트, 배치 전용 인스턴스) */
    val workerEnabled: Boolean = true,
    val pollInterval: Duration = Duration.ofSeconds(2),
    val batchSize: Int = 10,
    val maxAttempts: Int = 10,
    val backoff: Backoff = Backoff(),
    val staleLockTimeout: Duration = Duration.ofMinutes(10),
    /** 비우면 hostname + 랜덤 */
    val workerId: String = "",
) {
    data class Backoff(
        val initial: Duration = Duration.ofSeconds(30),
        val multiplier: Double = 2.0,
        val max: Duration = Duration.ofHours(1),
    ) {
        /** attempts 회 실패 후 다음 시도까지 대기 (1회차 실패 → initial, 2회차 → initial*multiplier …) */
        fun delayAfter(attempts: Int): Duration {
            val millis = initial.toMillis() * Math.pow(multiplier, (attempts - 1).coerceAtLeast(0).toDouble())
            return Duration.ofMillis(millis.toLong().coerceAtMost(max.toMillis()))
        }
    }
}
