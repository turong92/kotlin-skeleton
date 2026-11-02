package dev.sumin.skeleton.notification.push

import java.time.Duration
import java.time.temporal.ChronoUnit
import org.springframework.boot.convert.DurationUnit
import org.springframework.boot.context.properties.ConfigurationProperties

enum class LockScreen { FULL, HIDDEN }

/**
 * 푸시 알림. 모듈 기본은 꺼짐 — 앱 yml 이 켠다(`enabled: true`). 푸시로도 갈 종류는 **제품이 정한다**(기본 빈 목록 = 아무것도 안 간다).
 * ```yaml
 * skeleton:
 *   notification:
 *     push:
 *       enabled: true
 *       types: [NOTE_UPDATED, comment.created]
 * ```
 */
@ConfigurationProperties("skeleton.notification.push")
data class PushProperties(
    val enabled: Boolean = false,
    /** 푸시로도 갈 알림 type. 사람이 종류마다 「푸시만 끄기」를 할 수 있다 */
    val types: List<String> = emptyList(),
    /** FULL: 제목 · 본문 그대로, HIDDEN: 실제 내용을 푸시 서비스에 보내지 않고 중립 문구만(기본 factory 에만 적용) */
    val lockScreen: LockScreen = LockScreen.FULL,
    val hiddenTitle: String = "새 알림이 있습니다",
    val hiddenBody: String = "",
    val maxDevicesPerAccount: Int = 20,
    /** 푸시가 만료되는 시간 — 지나면 보내지 않는다 */
    val ttl: Duration = Duration.ofHours(24),
    /** 푸시 하나(기기 하나)의 최대 시도 수 */
    val maxAttempts: Int = 5,
    val http: Http = Http(),
    val openReport: OpenReport = OpenReport(),
    val staleCleanup: StaleCleanup = StaleCleanup(),
) {
    /** 앞뒤 공백을 떼고 빈 글자 · 중복을 뺀, 설정 순서 그대로의 목록. 광고 종류(`skeleton.notification.advertising.types`)는 푸시 채널이 따로 다룬다 - 사람이 푸시만 끄는 목록에는 들어가지 않는다 */
    val pushTypes: List<String> = types.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 푸시 클릭 열람 보고 - 비밀이 비면 꺼짐(페이로드에 `open` 이 없고 `POST /api/v1/push/opened` 도 없다) */
    data class OpenReport(
        /** 클릭 토큰 서명 비밀(32자 이상). 비밀이다 - 로그 · toString 에 싣지 않는다 */
        val secret: String = "",
    ) {
        init {
            require(secret.isEmpty() || secret.length >= MIN_SECRET_LENGTH) { "skeleton.notification.push.open-report.secret must be at least $MIN_SECRET_LENGTH characters (or empty to turn click reports off)" }
        }

        val enabled: Boolean get() = secret.isNotEmpty()

        override fun toString(): String = "OpenReport(enabled=$enabled)"

        companion object {
            const val MIN_SECRET_LENGTH = 32
        }
    }

    data class Http(
        /** false 면 `/api/v1/push` 를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
        val enabled: Boolean = true,
    )

    /** 오래 안 본 기기 정리 — 보관 정책은 앱이 정하므로 기본 꺼짐 */
    data class StaleCleanup(
        val enabled: Boolean = false,
        /** 단위 없이 쓰면 일이다(`60` = 60일). 1일 미만이면 시작이 실패한다 - 잘못 쓴 값이 전 계정의 기기를 지우지 않게 */
        @param:DurationUnit(ChronoUnit.DAYS) val staleAfter: Duration = Duration.ofDays(60),
        val interval: Duration = Duration.ofHours(6),
    ) {
        init {
            require(!enabled || staleAfter >= Duration.ofDays(1)) { "skeleton.notification.push.stale-cleanup.stale-after must be at least 1 day (a bare number counts days)" }
        }
    }
}
