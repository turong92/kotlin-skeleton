package dev.sumin.skeleton.common.web

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryRateLimitStoreTest {
    private val now = Instant.parse("2026-10-01T00:30:00Z")
    private val store = InMemoryFixedWindowRateLimitStore(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `a short-window call does not evict another key's longer-window counter`() {
        val hour = Duration.ofHours(1).toMillis()
        store.consume("login:alice", capacity = 2, windowMillis = hour, now = now)
        store.consume("login:alice", capacity = 2, windowMillis = hour, now = now)

        // a different key with a 1 s window runs the eviction pass — it must judge alice's counter by alice's own window
        store.consume("ip:1.2.3.4", capacity = 100, windowMillis = 1_000, now = now)

        val third = store.consume("login:alice", capacity = 2, windowMillis = hour, now = now)
        assertFalse(third.allowed, "alice already used 2 of 2 in this hour; the third call must be rejected")
    }

    @Test
    fun `sweep drops expired windows and keeps live ones`() {
        store.consume("short", capacity = 5, windowMillis = 1_000, now = now)
        store.consume("long", capacity = 5, windowMillis = 3_600_000, now = now)

        val removed = store.sweep(now.plusSeconds(5))

        assertEquals(1, removed)
        assertEquals(1, store.size())
        assertTrue(store.consume("long", capacity = 5, windowMillis = 3_600_000, now = now.plusSeconds(5)).remaining == 3)
    }
}
