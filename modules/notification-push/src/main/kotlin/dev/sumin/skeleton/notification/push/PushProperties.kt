package dev.sumin.skeleton.notification.push

import java.time.Duration
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
    /** 광고성 종류 - 기본 빈 목록 = 광고 규칙 꺼짐. 비어 있지 않으면 동의 정책 빈([AdvertisingConsentPolicy], `notification-push-legal`)이 있어야 시작한다 */
    val advertisingTypes: List<String> = emptyList(),
    val advertising: Advertising = Advertising(),
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
    val staleCleanup: StaleCleanup = StaleCleanup(),
) {
    /** 앞뒤 공백을 떼고 빈 글자 · 중복 · 광고 종류를 뺀, 설정 순서 그대로의 목록 — 푸시로 가고 사람이 푸시만 끌 수 있는 종류 */
    val pushTypes: List<String> =
        types.map { it.trim() }.filter { it.isNotEmpty() }.distinct() - advertisingTypes.map { it.trim() }.toSet()

    val advertisingTypeList: List<String> = advertisingTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * 광고 규칙(정보통신망법 §50 관행) - [advertisingTypes] 가 비어 있지 않을 때만 쓰인다. 법률 자문이 아니다: 문서 내용 · 전송자 정보 · 법 해석은 앱의 몫.
     * 야간 21~08시 · 2년은 법정 값이라 모듈 기본으로 둔다. [fallbackZone] · [senderName] 은 광고를 켜면 필수다(시스템 기본 시간대는 쓰지 않는다).
     */
    data class Advertising(
        /** `legal` 문서 종류 - 광고 수신 동의 */
        val consentType: String = "marketing",
        /** 야간(21~08시) 수신 별도 동의 - 다른 문서 · 다른 체크박스 */
        val nightConsentType: String = "marketing-night",
        val nightStart: String = "21:00",
        val nightEnd: String = "08:00",
        /** 받는 사람의 시간대를 모를 때(`PushRecipientZones` 가 null) - IANA 이름 */
        val fallbackZone: String? = null,
        val senderName: String? = null,
        /** 동의 유효 기간 - 가장 최근 동의 + 이 기간이 지나면 없는 것으로 본다 */
        val reconfirmAfter: Duration = Duration.ofDays(730),
        val reconfirmNoticeBefore: Duration = Duration.ofDays(30),
        val reconfirmScanInterval: Duration = Duration.ofHours(6),
        /** 재확인 안내를 누르면 가는 앱 안 경로 */
        val reconfirmLink: String = "/settings/notifications",
        val titlePrefix: String = "(광고)",
        val bodySuffix: String = "",
    )

    data class Http(
        /** false 면 `/api/v1/push` 를 등록하지 않는다 (앱이 자기 컨트롤러를 둘 때) */
        val enabled: Boolean = true,
    )

    /** 오래 안 본 기기 정리 — 보관 정책은 앱이 정하므로 기본 꺼짐 */
    data class StaleCleanup(
        val enabled: Boolean = false,
        val staleAfter: Duration = Duration.ofDays(60),
        val interval: Duration = Duration.ofHours(6),
    )
}
