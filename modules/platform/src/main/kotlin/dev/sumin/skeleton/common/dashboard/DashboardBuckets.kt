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

    /**
     * 시각이 붙은 표본([samples])을 칸에 나눠 칸마다 [reduce] 로 숫자 하나를 만든다 — 범위를 질의 한 번으로 읽고 여기서 나누므로 칸 수만큼 질의하지 않는다.
     * 범위 밖 표본은 버린다. 예: 건수 `{ it.size.toLong() }`, 합 `{ it.sum() }`, 서로 다른 사람 `{ it.distinct().size.toLong() }`
     */
    fun <T> series(range: DashboardRange, interval: DashboardInterval, samples: List<Pair<Instant, T>>, reduce: (List<T>) -> Long): List<DashboardPoint> {
        val buckets = of(range, interval)
        val starts = buckets.map { it.from }
        val groups = List(buckets.size) { ArrayList<T>() }
        samples.forEach { (at, item) ->
            if (at.isBefore(range.from) || !at.isBefore(range.to)) return@forEach
            var low = 0
            var high = starts.size - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (starts[mid].isAfter(at)) high = mid - 1 else low = mid
            }
            groups[low] += item
        }
        return buckets.mapIndexed { i, bucket -> DashboardPoint(bucket.from, reduce(groups[i])) }
    }

    private fun minOf(a: Instant, b: Instant) = if (a.isBefore(b)) a else b
}
