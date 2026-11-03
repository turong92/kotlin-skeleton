package dev.sumin.skeleton.waitingroom

import java.time.Instant

/**
 * 입장 번호(서빙) = 오픈 시각부터 속도 구간을 적분한 값(밀리초 단위, 내림). **시계와 구간 표만으로 정해지므로** 어느 인스턴스가 계산해도 같다(NTP 전제).
 * 번호 n 이 입장하는 순간 = `서빙 >= n` 이 처음 되는 밀리초 - 마지막 구간의 속도가 끝없이 이어진다고 본다(속도 0 이면 닿지 않는다).
 */
internal object Serving {
    private const val MILLIS_PER_MINUTE = 60_000L

    private class Segment(val startMs: Long, val perMinute: Long)

    /** 오픈 시각에 유효한 속도로 시작하고, 그 뒤에 시작하는 구간이 이어진다 */
    private fun segments(opensAt: Instant, rates: List<RateSegment>): List<Segment> {
        val sorted = rates.sortedBy { it.fromAt }
        val opens = opensAt.toEpochMilli()
        val initial = sorted.lastOrNull { it.fromAt <= opensAt }?.perMinute?.toLong() ?: 0L
        return listOf(Segment(opens, initial)) + sorted.filter { it.fromAt > opensAt }.map { Segment(it.fromAt.toEpochMilli(), it.perMinute.toLong()) }
    }

    fun served(opensAt: Instant, rates: List<RateSegment>, now: Instant): Long {
        val nowMs = now.toEpochMilli()
        val segs = segments(opensAt, rates)
        var total = 0L   // 분당 명 x 밀리초 - 마지막에 한 번만 나눠 내린다
        for ((i, seg) in segs.withIndex()) {
            val endMs = minOf(nowMs, segs.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE)
            if (endMs > seg.startMs) total += seg.perMinute * (endMs - seg.startMs)
        }
        return total / MILLIS_PER_MINUTE
    }

    fun admitAt(opensAt: Instant, rates: List<RateSegment>, position: Long): Instant? {
        val target = position * MILLIS_PER_MINUTE
        val segs = segments(opensAt, rates)
        var cumulative = 0L
        for ((i, seg) in segs.withIndex()) {
            if (seg.perMinute == 0L) continue
            val next = segs.getOrNull(i + 1)
            val capacity = if (next == null) Long.MAX_VALUE else seg.perMinute * (next.startMs - seg.startMs)
            if (next == null || cumulative + capacity >= target) {
                val needMs = Math.ceilDiv(target - cumulative, seg.perMinute)
                return Instant.ofEpochMilli(seg.startMs + needMs)
            }
            cumulative += capacity
        }
        return null
    }
}
