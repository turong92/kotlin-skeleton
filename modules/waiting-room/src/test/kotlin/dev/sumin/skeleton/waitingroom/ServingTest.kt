package dev.sumin.skeleton.waitingroom

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 서빙(입장 번호) 계산 — 시계와 구간 표만으로 정해진다. 가짜 시계 대신 시각을 직접 넣는다 */
class ServingTest {
    private val opens = Instant.parse("2026-11-03T12:00:00Z")

    @Test
    fun `at 100 per minute exactly 100 are served after 60 seconds and not one before`() {
        val rates = listOf(RateSegment(opens, 100))

        assertEquals(0, Serving.served(opens, rates, opens.minusSeconds(5)))
        assertEquals(99, Serving.served(opens, rates, opens.plusSeconds(60).minusMillis(1)))
        assertEquals(100, Serving.served(opens, rates, opens.plusSeconds(60)))
        assertEquals(200, Serving.served(opens, rates, opens.plusSeconds(120)))
    }

    @Test
    fun `a paused stretch serves nobody and the count resumes after it`() {
        val rates = listOf(RateSegment(opens, 100), RateSegment(opens.plusSeconds(60), 0), RateSegment(opens.plusSeconds(180), 60))

        assertEquals(100, Serving.served(opens, rates, opens.plusSeconds(179)))
        assertEquals(100, Serving.served(opens, rates, opens.plusSeconds(180)))
        assertEquals(160, Serving.served(opens, rates, opens.plusSeconds(240)))
    }

    @Test
    fun `the admission moment of a number is the first millisecond it is served`() {
        val rates = listOf(RateSegment(opens, 100), RateSegment(opens.plusSeconds(60), 0), RateSegment(opens.plusSeconds(180), 60))

        assertEquals(opens.plusSeconds(60), Serving.admitAt(opens, rates, 100))
        assertEquals(opens.plusSeconds(180).plusSeconds(1), Serving.admitAt(opens, rates, 101))
        assertEquals(opens.plusMillis(600), Serving.admitAt(opens, rates, 1))
        assertEquals(Serving.admitAt(opens, rates, 130)!!, opens.plusSeconds(180).plusSeconds(30))
    }

    @Test
    fun `a number the rate never reaches has no admission moment`() {
        val rates = listOf(RateSegment(opens, 100), RateSegment(opens.plusSeconds(60), 0))

        assertNull(Serving.admitAt(opens, rates, 101))
    }

    @Test
    fun `a rate set before the opening counts from the opening`() {
        val rates = listOf(RateSegment(opens, 100), RateSegment(opens.minusSeconds(600), 10))

        assertEquals(100, Serving.served(opens, rates, opens.plusSeconds(60)))
    }
}
