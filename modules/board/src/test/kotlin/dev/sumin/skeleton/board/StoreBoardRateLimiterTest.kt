package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreBoardRateLimiterTest {
    private val clock = Clock.fixed(T0, ZoneOffset.UTC)
    private val limiter = StoreBoardRateLimiter(
        store = InMemoryFixedWindowRateLimitStore(clock).let { s -> { s } },
        properties = BoardProperties.RateLimit(enabled = true, capacity = 2, window = Duration.ofMinutes(1)),
        clock = clock,
    )

    @Test
    fun `an account is refused after its capacity in one window`() {
        assertTrue(limiter.tryAcquire("a", "post"))
        assertTrue(limiter.tryAcquire("a", "post"))
        assertFalse(limiter.tryAcquire("a", "post"))
    }

    @Test
    fun `accounts and actions have separate budgets`() {
        repeat(2) { limiter.tryAcquire("a", "post") }
        assertTrue(limiter.tryAcquire("b", "post"))
        assertTrue(limiter.tryAcquire("a", "comment"))
    }

    @Test
    fun `the noop limiter never refuses`() {
        repeat(1000) { assertTrue(NoopBoardRateLimiter.tryAcquire("a", "post")) }
    }
}
