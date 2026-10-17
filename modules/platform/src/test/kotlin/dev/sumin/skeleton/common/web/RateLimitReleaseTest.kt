package dev.sumin.skeleton.common.web

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimitReleaseTest {
    private val now = Instant.ofEpochMilli(10_000)

    @Test
    fun `release gives one count back so a verified attempt does not wear the limit down`() {
        val store = InMemoryFixedWindowRateLimitStore()
        assertTrue(store.consume("k", 2, 60_000, now).allowed)
        store.release("k", 60_000, now)
        assertTrue(store.consume("k", 2, 60_000, now).allowed)
        assertTrue(store.consume("k", 2, 60_000, now).allowed)
        assertFalse(store.consume("k", 2, 60_000, now).allowed)
    }

    @Test
    fun `release never goes below zero and ignores unknown keys`() {
        val store = InMemoryFixedWindowRateLimitStore()
        store.release("missing", 60_000, now)
        assertTrue(store.consume("k", 1, 60_000, now).allowed)
        store.release("k", 60_000, now)
        store.release("k", 60_000, now)
        assertTrue(store.consume("k", 1, 60_000, now).allowed)
        assertFalse(store.consume("k", 1, 60_000, now).allowed)
    }

    @Test
    fun `a store without release keeps counting everything (the default)`() {
        val counts = object : RateLimitStore {
            var n = 0
            override fun consume(key: String, capacity: Int, windowMillis: Long, now: Instant) = RateLimitDecision(++n <= capacity, capacity, 0, now)
        }
        counts.consume("k", 1, 1000, now)
        counts.release("k", 1000, now)
        assertFalse(counts.consume("k", 1, 1000, now).allowed)
    }
}
