package dev.sumin.skeleton.notification.broadcast

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 공지 발송(전체 · 대상 공지 → 알림함 + 푸시). 모듈 기본은 꺼짐 — 앱 yml 이 켠다. 공지 종류는 **제품이 정한다**(기본 빈 목록, 켰는데 비면 시작이 실패한다).
 * ```yaml
 * skeleton:
 *   notification:
 *     broadcast:
 *       enabled: true
 *       types: [notice.service]
 *       admin: { enabled: true }
 * ```
 */
@ConfigurationProperties("skeleton.notification.broadcast")
data class BroadcastProperties(
    val enabled: Boolean = false,
    /** 운영자가 고를 알림 종류 */
    val types: List<String> = emptyList(),
    /** 알림함 topic (화면의 「공지」 탭 거르기에 쓴다) */
    val topic: String = "broadcast",
    /** 한 묶음(= 알림 한 번 · 작업 하나)의 받는 사람 수. 1~1000 */
    val batchSize: Int = 200,
    /** 분당 받는 사람 수 — 묶음 사이 간격 = batch-size / 이 값 분 */
    val recipientsPerMinute: Int = 6000,
    /** 단계 작업 하나의 최대 시도 수(큐의 백오프로 재시도) - 다 쓰면 공지는 FAILED */
    val stepMaxAttempts: Int = 5,
    /** 예약은 지금부터 이 기간 안 */
    val maxScheduleAhead: Duration = Duration.ofDays(90),
    val testTitlePrefix: String = "[테스트] ",
    /** 운영자 한 명이 나에게 테스트를 다시 보내려면 기다릴 시간 */
    val testCooldown: Duration = Duration.ofSeconds(30),
    val admin: Admin = Admin(),
) {
    /** 앞뒤 공백을 떼고 빈 글자 · 중복을 뺀, 설정 순서 그대로의 목록 */
    val typeList: List<String> = types.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    data class Admin(
        /** true 면 운영자 경로가 열린다 (꺼져 있으면 경로가 없어 404) */
        val enabled: Boolean = false,
        val basePath: String = "/api/v1/admin/broadcasts",
    )

    /** 묶음 사이 간격 — 분당 한도를 지키게 */
    val stepInterval: Duration get() = Duration.ofMillis(batchSize.toLong() * 60_000L / recipientsPerMinute.coerceAtLeast(1))
}
