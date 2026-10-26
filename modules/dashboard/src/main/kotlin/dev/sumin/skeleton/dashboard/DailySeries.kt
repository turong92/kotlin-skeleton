package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.dashboard.DashboardFreshness
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 외부 출처 카드가 기간을 가르는 도우미 — 오늘은 실시간(출처를 직접, 캐시를 거쳐) 부르고, 어제까지는 일별 표에서 읽는다.
 * [split] 이 기간을 「표에서 읽을 지난 날」과 「오늘」로 가르고, [past] 가 지난 날의 행을 읽는다 (저장소가 없으면 `NO_HISTORY`).
 */
class DailySeries(private val store: () -> DashboardDailyStore?, private val zone: ZoneId, private val now: () -> Instant) {
    /** [pastFrom]..[pastTo] 는 양 끝 포함 날짜(없으면 null), [today] 는 기간이 오늘을 포함할 때 오늘 칸(기간 끝에서 잘림) */
    data class Split(val pastFrom: LocalDate?, val pastTo: LocalDate?, val today: DashboardRange?)

    fun split(range: DashboardRange): Split {
        val today = now().atZone(zone).toLocalDate()
        val first = range.from.atZone(zone).toLocalDate()
        val last = range.to.minusNanos(1).atZone(zone).toLocalDate()
        val pastTo = minOf(last, today.minusDays(1))
        val past = first.takeIf { !it.isAfter(pastTo) }
        val todayRange = if (!first.isAfter(today) && !last.isBefore(today)) {
            val start = today.atStartOfDay(zone).toInstant()
            DashboardRange(maxOf(start, range.from), minOf(today.plusDays(1).atStartOfDay(zone).toInstant(), range.to), zone)
        } else {
            null
        }
        return Split(past, past?.let { pastTo }, todayRange)
    }

    /** 지난 날이 없으면 빈 목록(`OK`), 있는데 저장소가 없으면 `NO_HISTORY`. 신선도는 `DAILY`(마지막 적재 시각) */
    fun past(source: String, kind: String, split: Split): DashboardResult<List<DashboardDailyRow>> {
        val from = split.pastFrom ?: return DashboardResult(DashboardResult.Status.OK, emptyList())
        val target = store() ?: return DashboardResult(DashboardResult.Status.NO_HISTORY)
        return DashboardResult(
            DashboardResult.Status.OK,
            target.read(source, kind, from, split.pastTo!!),
            DashboardFreshness(DashboardFreshness.Kind.DAILY, target.lastIngested(source)),
        )
    }
}
