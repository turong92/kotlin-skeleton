package dev.sumin.skeleton.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 사람이 읽는 문자열. 메일·내보내기·서버 렌더 문구용 (화면은 프론트 `@sumin/time` 이 같은 규칙으로).
 *
 * - 시간대 이름은 약어("KST", "CST" — 3개 나라가 겹침) 대신 `GMT+9` 식 오프셋 표기를 쓴다
 * - [LocalDate] 는 시간대 변환 없이 그대로 (생일을 브라질에서 봐도 3월 5일)
 */
class TimeFormatter(private val ctx: TimeContext) {
    fun format(
        instant: Instant,
        style: FormatStyle = FormatStyle.MEDIUM,
        zone: ZoneId = ctx.zone(),
        locale: Locale = ctx.locale(),
    ): String = DateTimeFormatter.ofLocalizedDateTime(style).withLocale(locale).withZone(zone).format(instant)

    fun format(date: LocalDate, style: FormatStyle = FormatStyle.LONG, locale: Locale = ctx.locale()): String =
        DateTimeFormatter.ofLocalizedDate(style).withLocale(locale).format(date)

    /** `GMT+9`, `GMT-3` — 로케일별 오프셋 표기. 약어보다 모호하지 않다 */
    fun zoneLabel(zone: ZoneId, at: Instant = ctx.now(), locale: Locale = ctx.locale()): String =
        DateTimeFormatter.ofPattern("O", locale).format(at.atZone(zone))

    /**
     * 이벤트 시간대와 보는 사람 시간대를 같이. 같은 시간대면 [DualTime.viewer] 는 null.
     * 예: event = "2026. 10. 5. 오후 9:00 GMT+9", viewer = "2026. 10. 5. 오전 9:00 GMT-3"
     */
    fun dual(
        moment: ZonedMoment,
        viewerZone: ZoneId = ctx.zone(),
        locale: Locale = ctx.locale(),
        style: FormatStyle = FormatStyle.MEDIUM,
    ): DualTime {
        val eventZone = moment.zoneId()
        val event = "${format(moment.at, style, eventZone, locale)} ${zoneLabel(eventZone, moment.at, locale)}"
        val sameOffset = eventZone.rules.getOffset(moment.at) == viewerZone.rules.getOffset(moment.at)
        val viewer = if (sameOffset) null else "${format(moment.at, style, viewerZone, locale)} ${zoneLabel(viewerZone, moment.at, locale)}"
        return DualTime(event, viewer)
    }
}

data class DualTime(val event: String, val viewer: String?)
