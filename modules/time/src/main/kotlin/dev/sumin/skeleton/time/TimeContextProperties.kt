package dev.sumin.skeleton.time

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ```yaml
 * skeleton:
 *   time:
 *     default-zone: Asia/Seoul       # 아무 정보도 없을 때
 *     default-locale: ko-KR
 *     zone-header: X-Time-Zone       # 프론트가 자동으로 붙이는 헤더 (react-skeleton src/lib/time)
 * ```
 */
@ConfigurationProperties("skeleton.time")
data class TimeContextProperties(
    val defaultZone: String = "Asia/Seoul",
    val defaultLocale: String = "ko-KR",
    val zoneHeader: String = "X-Time-Zone",
)
