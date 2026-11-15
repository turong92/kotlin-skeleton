package dev.sumin.skeleton.notification.scheduled

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 사람마다 따로 예약하는 푸시. **모듈 기본은 꺼짐** - 앱 yml 이 켠다. 예약의 계기(언제 무엇을 예약하나)는 앱 코드가 정한다.
 * ```yaml
 * skeleton:
 *   notification:
 *     scheduled:
 *       enabled: true
 *       type: reminder                 # 정보성 푸시의 알림 종류 - push.types 에도 넣는다
 *       advertising-type: reminder.ad  # 광고성 푸시의 종류 - advertising.types 와 push.types 에 넣는다(광고를 안 보내면 비운다)
 *       daily-limit: 3                 # 한 사람이 24시간에 받는 개수 상한 (비우면 상한 없음)
 * ```
 */
@ConfigurationProperties("skeleton.notification.scheduled")
data class ScheduledPushProperties(
    val enabled: Boolean = false,
    /** 정보성 예약의 알림 종류 - `notification.advertising.types` 에 있으면 안 된다 */
    val type: String = "",
    /** 광고성 예약의 알림 종류 - `notification.advertising.types` 안에 있어야 한다. 비면 광고성 예약은 거절된다 */
    val advertisingType: String = "",
    /** 알림함 topic */
    val topic: String = "scheduled",
    /** 한 사람이 24시간 안에 받는 최대 개수 - 닿으면 그 예약은 건너뛴다. 비우면(기본) 상한 없음 */
    val dailyLimit: Int? = null,
    /** 한 번 훑을 때 집는 최대 예약 수 */
    val batchSize: Int = 100,
    /** 훑기가 집은 예약을 다른 훑기가 다시 집지 못하는 시간 - 집은 쪽이 죽어도 이 시간 뒤 다시 나간다. 한 건을 처리하는 데 걸리는 시간보다 넉넉하게 */
    val lease: Duration = Duration.ofMinutes(5),
    /** 끝난(보냄 · 건너뜀) 키를 다시 예약해도 무시하는 기간 - 이 기간이 지나면 같은 키가 새 예약으로 되살아난다. 같은 계기를 한 통으로 막는 기간이다 */
    val dedupeWindow: Duration = Duration.ofDays(30),
    /** 끝난 줄을 표에서 지우는 나이(정리 기간) - dedupe-window 보다 짧으면 지운 키는 그 전에 다시 예약된다. 하루 상한을 켰으면 24시간 이상 */
    val keepFinished: Duration = Duration.ofDays(30),
    /** 보내려다 이만큼 실패한 예약은 포기하고 건너뛴다(`TOO_MANY_ATTEMPTS`) */
    val maxAttempts: Int = 5,
) {
    init {
        require(dailyLimit == null || dailyLimit >= 1) { "skeleton.notification.scheduled.daily-limit must be at least 1 (leave it unset for no limit)" }
        require(batchSize in 1..1000) { "skeleton.notification.scheduled.batch-size must be between 1 and 1000" }
        require(!lease.isNegative && !lease.isZero) { "skeleton.notification.scheduled.lease must be positive" }
        require(!keepFinished.isNegative && !keepFinished.isZero) { "skeleton.notification.scheduled.keep-finished must be positive" }
        require(!dedupeWindow.isNegative && !dedupeWindow.isZero) { "skeleton.notification.scheduled.dedupe-window must be positive" }
        require(maxAttempts >= 1) { "skeleton.notification.scheduled.max-attempts must be at least 1" }
        // 하루 상한은 끝난 줄을 세어 판정한다 - 24시간 안에 지워 버리면 상한이 조용히 풀린다
        require(dailyLimit == null || keepFinished >= Duration.ofHours(24)) { "skeleton.notification.scheduled.keep-finished must be at least 24h when daily-limit is set (the limit counts the finished rows)" }
    }
}
