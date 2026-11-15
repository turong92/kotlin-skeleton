package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRouteWindowTest {
    private val t0 = Instant.parse("2026-11-16T00:00:00Z")
    private var clock = t0
    private val time = TimeProvider { clock }

    private class FakeStore(var openUntil: Instant?) : LocalRouteWindowStore {
        var findCalls = 0

        override fun findOpen(now: Instant): LocalWindow? {
            findCalls++
            return openUntil?.takeIf { now.isBefore(it) }?.let { LocalWindow(1, "alice", "hotfix", Instant.EPOCH, it, null, null) }
        }

        override fun open(openedBy: String, reason: String, now: Instant, openUntil: Instant) = error("not used")

        override fun closeAll(closedBy: String, now: Instant): List<Long> = error("not used")
    }

    private fun window(store: FakeStore, ttl: Duration = Duration.ofSeconds(5)) = StoreLocalRouteWindow(store, ttl, time)

    @Test
    fun `closed without a store`() {
        assertFalse(ClosedLocalRouteWindow.isOpen(t0))
    }

    @Test
    fun `reads the store once inside the cache ttl`() {
        val store = FakeStore(t0.plusSeconds(3600))
        val window = window(store)

        repeat(3) { assertTrue(window.isOpen(clock)) }
        clock = t0.plusSeconds(4)
        assertTrue(window.isOpen(clock))

        assertEquals(1, store.findCalls)
    }

    @Test
    fun `reads again after the ttl`() {
        val store = FakeStore(t0.plusSeconds(3600))
        val window = window(store)
        assertTrue(window.isOpen(clock))

        store.openUntil = null
        clock = t0.plusSeconds(6)

        assertFalse(window.isOpen(clock))
        assertEquals(2, store.findCalls)
    }

    @Test
    fun `invalidate makes the next check read the store`() {
        val store = FakeStore(t0.plusSeconds(3600))
        val window = window(store)
        assertTrue(window.isOpen(clock))

        store.openUntil = null
        window.invalidate()

        assertFalse(window.isOpen(clock))
    }

    @Test
    fun `closed once the open until time has passed even while the cache still holds the window`() {
        val store = FakeStore(t0.plusSeconds(10))
        val window = window(store, ttl = Duration.ofMinutes(5))
        assertTrue(window.isOpen(clock))

        clock = t0.plusSeconds(11)

        assertFalse(window.isOpen(clock))
        assertEquals(1, store.findCalls)
    }
}
