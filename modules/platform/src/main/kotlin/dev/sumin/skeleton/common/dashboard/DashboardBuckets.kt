package dev.sumin.skeleton.common.dashboard

import java.time.Instant

/**
 * 그래프 시리즈의 칸 나누기 — 기여가 칸마다 같은 방식으로 세도록 한 곳에 둔다. 칸은 [range] 의 시작에서 시간대(`range.zone`)의 시 · 일 · 7일 단위로 끊고 (여름시간제 날도 달력 기준),
 * 마지막 칸은 `range.to` 에서 잘린다. 점의 시각(`at`)은 칸의 시작이다.
 */
object DashboardBuckets {
    fun of(range: DashboardRange, interval: DashboardInterval): List<DashboardRange> {
        val buckets = ArrayList<DashboardRange>()
        var start = range.from.atZone(range.zone)
        var index = 0L
        while (start.toInstant().isBefore(range.to)) {
            val next = when (interval) {
                DashboardInterval.HOUR -> range.from.atZone(range.zone).plusHours(index + 1)
                DashboardInterval.DAY -> range.from.atZone(range.zone).plusDays(index + 1)
                DashboardInterval.WEEK -> range.from.atZone(range.zone).plusDays(7 * (index + 1))
            }.toInstant()
            buckets += DashboardRange(start.toInstant(), minOf(next, range.to), range.zone)
            start = next.atZone(range.zone)
            index++
        }
        return buckets
    }

    /** 칸마다 [count] 를 불러 점을 만든다 */
    fun series(range: DashboardRange, interval: DashboardInterval, count: (DashboardRange) -> Long): List<DashboardPoint> =
        of(range, interval).map { DashboardPoint(it.from, count(it)) }

    private fun minOf(a: Instant, b: Instant) = if (a.isBefore(b)) a else b
}
