package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.slf4j.LoggerFactory

/**
 * 광고성 정보를 **보내도 되나**. [send] = 광고 수신 동의가 지금 유효하다(철회 · 만료 · 새 판 미동의가 아님). [night] = 야간(21~08시) 수신 동의도 유효하다.
 * [night] 은 [send] 가 참일 때만 뜻이 있다 - 게이트는 `send && night` 로 읽는다(광고 동의를 거두면 야간 동의도 같이 멈춘다).
 */
data class AdvertisingConsent(val send: Boolean, val night: Boolean)

/**
 * 광고 동의를 읽는 포트. 구현은 `notification-push-legal`(`legal` 의 동의 기록). 던지거나 모르는 사람이면 **안 보낸다**(실패 시 닫힘).
 *
 * 이 게이트는 **푸시 채널(웹 푸시 · FCM)에만** 있다. 메일 · 슬랙 · SSE 로 광고를 보내는 앱은 이 정책을 직접 불러 동의를 확인해야 한다 -
 * `notification` 계약은 광고를 모른다.
 */
fun interface AdvertisingConsentPolicy {
    fun consentOf(accountId: String, now: Instant): AdvertisingConsent
}

/** 받는 사람의 시간대 - 야간 판정에 쓴다. 앱이 구현한다(예: 계정 프로필의 시간대). null 이면 `advertising.fallback-zone` */
fun interface PushRecipientZones {
    fun zoneOf(accountId: String): ZoneId?
}

/** 알림 종류 이름 - 동의 변경 통지와 재확인 안내는 정보성이라 광고 종류로 설정할 수 없다 */
object AdvertisingNotificationTypes {
    const val CONSENT_CHANGED = "advertising.consent.changed"
    const val CONSENT_RECONFIRM = "advertising.consent.reconfirm"
    const val PREFIX = "advertising.consent."
}

sealed interface AdvertisingDecision {
    /** 안 보낸다 - 동의 없음 · 철회 · 만료 · 조회 실패 */
    data object Skip : AdvertisingDecision

    data object SendNow : AdvertisingDecision

    /** 야간이고 야간 동의가 없다 - [runAt] 이후에 보낸다(받는 사람 시간대의 다음 야간 끝) */
    data class Hold(val runAt: Instant) : AdvertisingDecision
}

/**
 * 광고 종류의 발송 규칙 한 곳 - 채널(넣을 때)과 처리기(집을 때)가 같은 판정을 쓴다. 광고 종류가 없으면([enabled] = false) 아무것도 하지 않는다.
 * [decide] 는 **절대 던지지 않는다**: 정책 · 시간대 조회가 실패하면 [AdvertisingDecision.Skip].
 * 구성이 덜 됐으면 [problems] 가 이유를 말한다(자동설정이 시작을 멈춘다). 구성이 덜 된 채로 [decide] 를 부르면 늘 [AdvertisingDecision.Skip] 이다.
 */
class AdvertisingGate(
    private val properties: PushProperties,
    private val policy: AdvertisingConsentPolicy?,
    private val zones: PushRecipientZones?,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val types = properties.advertisingTypeList.toSet()
    private val advertising = properties.advertising
    private val nightStart = runCatching { LocalTime.parse(advertising.nightStart.trim()) }.getOrNull()
    private val nightEnd = runCatching { LocalTime.parse(advertising.nightEnd.trim()) }.getOrNull()
    private val fallback = advertising.fallbackZone?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    val enabled: Boolean get() = types.isNotEmpty()

    fun isAdvertising(type: String): Boolean = type in types

    /** 시작을 막는 구성 문제들 - 비어 있으면 괜찮다. 광고 종류가 없으면 늘 비어 있다 */
    fun problems(): List<String> {
        if (!enabled) return emptyList()
        return buildList {
            if (policy == null) add("advertising-types needs an AdvertisingConsentPolicy bean - add the notification-push-legal module (it reads the consent records of legal)")
            if (advertising.fallbackZone.isNullOrBlank()) {
                add("advertising.fallback-zone is required when advertising-types is set (the system time zone is never used), e.g. Asia/Seoul")
            } else if (fallback == null) {
                add("advertising.fallback-zone '${advertising.fallbackZone}' is not a time zone name")
            }
            if (advertising.senderName.isNullOrBlank()) add("advertising.sender-name is required when advertising-types is set (the notices name the sender)")
            if (nightStart == null) add("advertising.night-start '${advertising.nightStart}' is not a HH:mm time")
            if (nightEnd == null) add("advertising.night-end '${advertising.nightEnd}' is not a HH:mm time")
            if (nightStart != null && nightStart == nightEnd) add("advertising.night-start and advertising.night-end must differ (equal times would switch the night rule off)")
            if (advertising.titlePrefix.isBlank()) add("advertising.title-prefix must not be blank (the advertising mark is mandatory)")
            if (properties.ttl < MIN_TTL) add("ttl must be at least 12 hours when advertising-types is set (a night hold can last 11 hours and must not expire the advertisement)")
            if (advertising.consentType == advertising.nightConsentType) add("advertising.consent-type and advertising.night-consent-type must be different documents")
            val clash = types.filter { it.startsWith(AdvertisingNotificationTypes.PREFIX) }
            if (clash.isNotEmpty()) add("advertising-types must not contain the informational consent notices (${clash.joinToString()})")
        }
    }

    /** 이 사람에게 지금 광고를 보내도 되나 - 보낼 시각까지 같이 답한다. 던지지 않는다 */
    fun decide(accountId: String): AdvertisingDecision {
        if (!enabled || policy == null || nightStart == null || nightEnd == null) return AdvertisingDecision.Skip
        return try {
            val now = time.now()
            val consent = policy.consentOf(accountId, now)
            if (!consent.send) return AdvertisingDecision.Skip
            if (consent.night) return AdvertisingDecision.SendNow
            val zone = zoneOrNull(accountId) ?: return AdvertisingDecision.Skip
            val local = now.atZone(zone)
            if (!isNight(local.toLocalTime())) AdvertisingDecision.SendNow else AdvertisingDecision.Hold(nextNightEnd(now, zone, nightEnd))
        } catch (e: Exception) {
            log.warn("advertising consent check failed for account={} exception={}", accountId, e.javaClass.simpleName)
            AdvertisingDecision.Skip
        }
    }

    /** 이 사람에게 적용되는 시간대 - 앱이 답한 것, 없으면 `fallback-zone`. 구성이 덜 됐으면 던진다(안내 화면용 - 발송 판정은 [decide]) */
    fun zoneOf(accountId: String): ZoneId = zoneOrNull(accountId) ?: error("no time zone for $accountId")

    /** 제목 앞에 광고 표기를(이미 있으면 그대로), 본문 끝에 [PushProperties.Advertising.bodySuffix] 를 - 본문은 푸시 한도 안에서 */
    fun brand(message: PushMessage): PushMessage {
        val prefix = advertising.titlePrefix.trim()
        val title = if (prefix.isEmpty() || message.title.trimStart().startsWith(prefix)) message.title else "$prefix ${message.title}"
        val suffix = advertising.bodySuffix
        if (suffix.isEmpty() || message.body?.endsWith(suffix) == true) return message.copy(title = title)   // 이미 붙었으면 그대로 - 멱등
        val room = (PushText.MAX_BODY_BYTES - suffix.toByteArray(Charsets.UTF_8).size - 1).coerceAtLeast(0)   // -1: 줄바꿈
        val body = message.body?.let { PushText.truncateUtf8(it, room) }.orEmpty()
        val glue = if (body.isEmpty() || body.endsWith("\n")) "" else "\n"
        return message.copy(title = title, body = body + glue + suffix)
    }

    private fun zoneOrNull(accountId: String): ZoneId? = (zones?.zoneOf(accountId)) ?: fallback

    private fun isNight(t: LocalTime): Boolean {
        val start = nightStart!!
        val end = nightEnd!!
        return when {
            start.isAfter(end) -> !t.isBefore(start) || t.isBefore(end)
            start.isBefore(end) -> !t.isBefore(start) && t.isBefore(end)
            else -> false
        }
    }

    /** [now] 보다 뒤인 가장 가까운 현지 [end] 시각 - 서머타임 구간은 `ZonedDateTime` 이 푼다 */
    private fun nextNightEnd(now: Instant, zone: ZoneId, end: LocalTime): Instant {
        var date: LocalDate = now.atZone(zone).toLocalDate()
        while (true) {
            val candidate = date.atTime(end).atZone(zone).toInstant()
            if (candidate.isAfter(now)) return candidate
            date = date.plusDays(1)
        }
    }

    private companion object {
        val MIN_TTL: java.time.Duration = java.time.Duration.ofHours(12)
    }
}
