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
 *     unhandled-type-grace: 1d  # 이 워커에 처리기가 없는 종류는 집지 않고 둔다(배포 중 새 버전이 넣은 잡) — 이 기간 동안 아무도 안 집으면 DEAD
 *     propagated-mdc-keys: [traceId]   # 넣는 쪽 MDC 에서 작업 줄로 옮겨 워커 로그에 복원할 키
 *     mode: polling              # on-request 면 폴링 스레드 없이 깨우기 주소(wake)가 부를 때만 처리 — 0대로 내려가는 배포용 (docs/cloud-run-scale-to-zero.md)
 *     wake:
 *       path: /internal/jobs/drain
 *       secret: ""               # 요청 헤더 X-Wake-Secret 과 맞춰 본다. 비면 주소는 닫혀 있다(404)
 *       time-budget: 25s
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
    /**
     * 이 워커가 처리기를 모르는 잡 종류는 집지 않고 PENDING 으로 둔다 — 신구 버전이 섞인 배포 동안 새 버전이 넣은 잡을 옛 워커가 DEAD 로 만들지 않게.
     * 실행 시각이 이 기간보다 더 지났는데도 아무 워커도 집지 못했으면(처리기가 어디에도 없다) 그때 DEAD + 로그 + 듣는 쪽 알림. 배포 한 번이 끝나는 시간보다 길게 둔다
     */
    val unhandledTypeGrace: Duration = Duration.ofDays(1),
    /** 비우면 hostname + 랜덤 */
    val workerId: String = "",
    /** 요청 → 작업으로 건너가는 MDC 키. 비우면 문맥을 싣지 않는다 */
    val propagatedMdcKeys: List<String> = listOf("traceId"),
    val retention: Retention = Retention(),
    /** [Mode.ON_REQUEST] 면 폴링 스레드를 띄우지 않는다 — 깨우기 주소가 불릴 때만 처리한다. 기본 [Mode.POLLING] 은 지금까지와 같다 */
    val mode: Mode = Mode.POLLING,
    val wake: Wake = Wake(),
) {
    enum class Mode { POLLING, ON_REQUEST }

    /** 요청 구동 모드의 깨우기 주소 (Cloud Scheduler 같은 밖의 호출자가 부른다) */
    data class Wake(
        /** 깨우기 주소 (POST). 값 하나를 바꿔 다른 경로로 옮길 수 있다 */
        val path: String = "/internal/jobs/drain",
        /** 공유 비밀값 — 요청 헤더 `X-Wake-Secret` 과 상수 시간으로 맞춰 본다. 비어 있으면 주소가 닫혀 있다(404). 보호 환경(stage · prod)에서는 24자 미만이면 기동 실패 */
        val secret: String = "",
        /** 한 번 불렸을 때 새 묶음을 집기를 멈추는 시간 — 이 시간을 넘기면 지금 묶음까지만 끝내고 돌려준다 (남은 일감은 다음 호출이 이어받는다) */
        val timeBudget: Duration = Duration.ofSeconds(25),
    )

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
