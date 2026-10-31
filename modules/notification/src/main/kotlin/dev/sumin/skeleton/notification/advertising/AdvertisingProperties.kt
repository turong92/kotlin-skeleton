package dev.sumin.skeleton.notification.advertising

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 광고성 정보 규칙(정보통신망법 §50 관행) - [types] 가 비어 있지 않을 때만 쓰인다. 법률 자문이 아니다: 문서 내용 · 전송자 정보 · 법 해석은 앱의 몫.
 * 채널과 무관하다: 푸시 채널이 이 규칙([AdvertisingGate])을 부르고, 광고성 메일 · 슬랙 등을 보내는 앱도 보내기 전에 같은 규칙을 부른다.
 * 야간 21~08시 · 2년은 법정 값이라 모듈 기본으로 둔다. [fallbackZone] · [senderName] 은 광고를 켜면 필수다(시스템 기본 시간대는 쓰지 않는다).
 * ```yaml
 * skeleton:
 *   notification:
 *     advertising:
 *       types: [promo.tips]
 *       fallback-zone: Asia/Seoul
 *       sender-name: Example Co.
 * ```
 */
@ConfigurationProperties("skeleton.notification.advertising")
data class AdvertisingProperties(
    /** 광고성 알림 type - 기본 빈 목록 = 광고 규칙 꺼짐. 비어 있지 않으면 동의 정책 빈([AdvertisingConsentPolicy], `notification-legal`)이 있어야 시작한다 */
    val types: List<String> = emptyList(),
    /** `legal` 문서 종류 - 광고 수신 동의 */
    val consentType: String = "marketing",
    /** 야간(21~08시) 수신 별도 동의 - 다른 문서 · 다른 체크박스 */
    val nightConsentType: String = "marketing-night",
    val nightStart: String = "21:00",
    val nightEnd: String = "08:00",
    /** 받는 사람의 시간대를 모를 때(`AdvertisingRecipientZones` 가 null) - IANA 이름 */
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
) {
    /** 앞뒤 공백을 떼고 빈 글자 · 중복을 뺀, 설정 순서 그대로의 목록 */
    val typeList: List<String> = types.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
