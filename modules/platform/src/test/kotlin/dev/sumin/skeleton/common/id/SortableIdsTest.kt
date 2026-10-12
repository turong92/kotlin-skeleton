package dev.sumin.skeleton.common.id

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import java.util.random.RandomGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SortableIdsTest {
    private class MovableClock(start: Long) : Clock() {
        val millis = AtomicLong(start)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
    }

    private val zeros = object : RandomGenerator {
        override fun nextLong() = 0L
    }

    @Test
    fun `the first ten characters are the millisecond clock, the rest the random part`() {
        val clock = MovableClock(1)
        val ids = SortableIds(clock, zeros)
        assertEquals("ord_" + "0000000001" + "0000000000000000", ids.newId("ord_"))
        clock.millis.set(32)
        assertEquals("0000000010" + "0000000000000000", ids.newId())
    }

    @Test
    fun `ids from the same millisecond keep rising`() {
        val ids = SortableIds(MovableClock(1_700_000_000_000), java.security.SecureRandom())
        val made = List(5_000) { ids.newId("x_") }
        assertEquals(made.sorted(), made)
        assertEquals(made.size, made.toSet().size)
    }

    @Test
    fun `a clock that steps back does not break the order`() {
        val clock = MovableClock(1_700_000_000_000)
        val ids = SortableIds(clock, java.security.SecureRandom())
        val a = ids.newId()
        clock.millis.set(1_699_999_990_000)
        val b = ids.newId()
        assertTrue(a < b, "$a < $b")
    }

    @Test
    fun `a later millisecond sorts after an earlier one whatever the random part is`() {
        val clock = MovableClock(1_700_000_000_000)
        val ids = SortableIds(clock, java.security.SecureRandom())
        val earlier = List(50) { ids.newId() }
        clock.millis.incrementAndGet()
        val later = List(50) { ids.newId() }
        assertTrue(earlier.max() < later.min())
    }

    @Test
    fun `an id is 26 lowercase Crockford base32 characters after the prefix`() {
        val id = SortableIds().newId("acc_")
        assertTrue(Regex("acc_[0-9a-hjkmnp-tv-z]{26}").matches(id), id)
        assertEquals(30, id.length)
    }

    @Test
    fun `a counter that overflows the random part moves to the next millisecond`() {
        val ones = object : RandomGenerator {
            override fun nextLong() = -1L
        }
        val ids = SortableIds(MovableClock(5), ones)
        val first = ids.newId()
        val second = ids.newId()
        assertTrue(first < second, "$first < $second")
    }

    @Test
    fun `a UUIDv7 carries version 7, the RFC variant and the millisecond clock`() {
        val uuid = SortableIds(MovableClock(0x0123456789ab), zeros).newUuid()
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals(0x0123456789abL, uuid.mostSignificantBits ushr 16)
    }

    @Test
    fun `UUIDv7s from one millisecond keep rising as text`() {
        val ids = SortableIds(MovableClock(1_700_000_000_000), java.security.SecureRandom())
        val made = List(5_000) { ids.newUuid().toString() }
        assertEquals(made.sorted(), made)
        assertEquals(made.size, made.toSet().size)
    }
}
