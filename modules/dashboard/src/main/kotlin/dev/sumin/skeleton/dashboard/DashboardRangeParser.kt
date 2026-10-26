package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** 요청한 기간 — 대시보드 시간대의 날짜 `[fromDate, toDate]`(양 끝 포함)와 그 날짜가 뜻하는 [range] */
data class DashboardPeriod(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val range: DashboardRange,
    /** 바로 앞 같은 길이의 기간 */
    val previous: DashboardRange,
    val previousFromDate: LocalDate,
    val previousToDate: LocalDate,
    val interval: DashboardInterval,
    val zone: ZoneId,
)

/** `from` · `to` 날짜 문자열 → [DashboardPeriod]. 둘 다 없으면 오늘 하루, 하나만 있거나 날짜가 아니거나 거꾸로거나 366일을 넘으면 `DASHBOARD.RANGE_INVALID` */
class DashboardRangeParser(private val zoneSource: () -> ZoneId, private val now: () -> Instant) {
    constructor(zone: ZoneId, now: () -> Instant) : this({ zone }, now)

    /** 지금 프로젝트의 대시보드 시간대 */
    val zone: ZoneId get() = zoneSource()

    fun parse(from: String?, to: String?): DashboardPeriod {
        val zone = zoneSource()
        val fromDate: LocalDate
        val toDate: LocalDate
        if (from == null && to == null) {
            fromDate = now().atZone(zone).toLocalDate()
            toDate = fromDate
        } else {
            if (from == null || to == null) throw invalid("from and to go together")
            fromDate = date("from", from)
            toDate = date("to", to)
        }
        if (fromDate.isAfter(toDate)) throw invalid("from is after to")
        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        if (days > MAX_DAYS) throw invalid("at most $MAX_DAYS days")
        val previousFrom = fromDate.minusDays(days)
        val previousTo = fromDate.minusDays(1)
        return DashboardPeriod(
            fromDate = fromDate,
            toDate = toDate,
            range = rangeOf(zone, fromDate, toDate),
            previous = rangeOf(zone, previousFrom, previousTo),
            previousFromDate = previousFrom,
            previousToDate = previousTo,
            interval = when {
                days == 1L -> DashboardInterval.HOUR
                days <= MAX_DAILY_DAYS -> DashboardInterval.DAY
                else -> DashboardInterval.WEEK
            },
            zone = zone,
        )
    }

    private fun rangeOf(zone: ZoneId, from: LocalDate, to: LocalDate) =
        DashboardRange(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(), zone)

    private fun date(name: String, value: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw invalid("$name is not a date (yyyy-MM-dd)")
        }

    private fun invalid(detail: String) = DashboardException(DashboardErrorCode.RANGE_INVALID, "Invalid date range: $detail")

    companion object {
        const val MAX_DAYS = 366L
        const val MAX_DAILY_DAYS = 92L
    }
}
