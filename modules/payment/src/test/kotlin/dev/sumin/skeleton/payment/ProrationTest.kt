package dev.sumin.skeleton.payment

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 일 단위 일할 - 첫날 · 마지막 날 · 자정(앱 시간대 Asia/Seoul) 직전/직후 · 하루보다 짧은 기간. */
class ProrationTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z") // 서울 1월 1일 09:00
    private val end = Instant.parse("2026-01-31T00:00:00Z") // 서울 1월 31일 09:00 - 30일

    private fun left(now: String) = Proration.share(start, end, Instant.parse(now)).first

    @Test
    fun `the first day counts as used, the whole period is thirty days and the last day leaves nothing`() {
        assertEquals(30L to 30L, Proration.share(start, end, start))
        assertEquals(30, left("2026-01-01T00:00:01Z"), "a second into the first day is still the first day")
        assertEquals(1, left("2026-01-30T00:00:00Z"))
        assertEquals(0, left("2026-01-31T00:00:00Z"), "on the end date nothing is left")
        assertEquals(0, left("2026-02-05T00:00:00Z"))
        assertEquals(30, left("2025-12-01T00:00:00Z"), "before the start is the whole period")
    }

    @Test
    fun `the day turns at midnight of the app zone, not at the instant the period started`() {
        assertEquals(30, left("2026-01-01T14:59:59Z"), "23:59:59 in Seoul")
        assertEquals(29, left("2026-01-01T15:00:00Z"), "00:00 in Seoul")
    }

    @Test
    fun `the amount is rounded down to the smallest unit`() {
        fun prorated(now: String) = Proration.remaining(3_900, start, end, Instant.parse(now))
        assertEquals(3_900, prorated("2026-01-01T10:00:00Z"))
        assertEquals(3_770, prorated("2026-01-02T00:00:00Z"), "29 days of 30")
        assertEquals(2_990, prorated("2026-01-08T00:00:01Z"), "23 days of 30")
        assertEquals(130, prorated("2026-01-30T00:00:00Z"))
        assertEquals(0, prorated("2026-01-31T00:00:00Z"))
        assertEquals(3_333, Proration.remaining(10_000, start, end, Instant.parse("2026-01-21T00:00:00Z")), "10 of 30 days of 10,000")
    }

    @Test
    fun `a period shorter than a day is prorated by time so a three-minute test cycle can be tried`() {
        val short = start.plus(Duration.ofMinutes(3))
        assertEquals(1_950, Proration.remaining(3_900, start, short, start.plusSeconds(90)))
        assertEquals(0, Proration.remaining(3_900, start, short, short))
    }
}
