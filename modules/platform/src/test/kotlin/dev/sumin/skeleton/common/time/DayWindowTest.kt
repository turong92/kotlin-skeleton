package dev.sumin.skeleton.common.time

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DayWindowTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `the day is the zone's calendar day - Seoul midnight is 15 00 UTC`() {
        val before = DayWindow.containing(Instant.parse("2026-11-18T14:59:59Z"), seoul)
        assertEquals(Instant.parse("2026-11-17T15:00:00Z"), before.from)
        assertEquals(Instant.parse("2026-11-18T15:00:00Z"), before.to)

        val at = DayWindow.containing(Instant.parse("2026-11-18T15:00:00Z"), seoul)
        assertEquals(Instant.parse("2026-11-18T15:00:00Z"), at.from)
        assertEquals(Instant.parse("2026-11-19T15:00:00Z"), at.to)
    }
}
