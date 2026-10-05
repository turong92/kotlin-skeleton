package dev.sumin.skeleton.alert.jdbc

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   alert-jdbc:
 *     enabled: true
 *     retention:
 *       enabled: false   # true 면 기록 중에 오래된 줄을 지운다
 *       keep: 90d
 * ```
 */
@ConfigurationProperties("skeleton.alert-jdbc")
data class AlertJdbcProperties(
    /** false 면 DB 저장소를 만들지 않는다 (기본 메모리 저장소로 돌아간다) */
    val enabled: Boolean = true,
    val retention: Retention = Retention(),
) {
    /** 마지막으로 온 지 [keep] 가 지난 줄 정리 — 기본 꺼짐: 줄을 지우는 건 보관 정책이라 앱이 정한다 */
    data class Retention(
        val enabled: Boolean = false,
        val keep: Duration = Duration.ofDays(90),
        /** 정리를 도는 간격 (기록하는 호출 안에서 이 간격마다 한 번) */
        val interval: Duration = Duration.ofHours(6),
    )
}
