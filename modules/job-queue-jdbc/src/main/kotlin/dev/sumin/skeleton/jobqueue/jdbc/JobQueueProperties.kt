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
 *     stale-lock-timeout: 10m   # RUNNING 인 채로 이 시간이 지나면 워커가 죽은 것으로 보고 PENDING 으로 되돌린다 (시도를 다 썼으면 DEAD)
 *     propagated-mdc-keys: [traceId]   # 넣는 쪽 MDC 에서 작업 줄로 옮겨 워커 로그에 복원할 키
 *     retention:
 *       enabled: false           # true 면 워커가 DONE · DEAD 줄을 보관 기간 뒤 지운다
 *       done: 14d
 *       dead: 90d
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
    /** 요청 → 작업으로 건너가는 MDC 키. 비우면 문맥을 싣지 않는다 */
    val propagatedMdcKeys: List<String> = listOf("traceId"),
    val retention: Retention = Retention(),
) {
    /** 끝난 줄 정리 — 기본은 꺼짐: 줄을 지우는 건 보관 정책(사후 확인 · 감사)이라 앱이 정한다 */
    data class Retention(
        val enabled: Boolean = false,
        /** DONE 줄을 이 기간 뒤 지운다 (`updated_at` 기준) */
        val done: Duration = Duration.ofDays(14),
        /** DEAD 줄을 이 기간 뒤 지운다 — 운영자가 보고 다시 시도하거나 버릴 시간 */
        val dead: Duration = Duration.ofDays(90),
        /** 한 번에 지우는 줄 수 — 큰 DELETE 가 표를 오래 잠그지 않게 나눠 지운다 */
        val batchSize: Int = 1000,
        /** 정리를 도는 간격 (워커 폴링 안에서 이 간격마다 한 번) */
        val interval: Duration = Duration.ofHours(1),
    )

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
