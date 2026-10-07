package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 두 DB 에서 — 같은 일을 여러 인스턴스가 동시에 잡아도 임대는 하나만 이기고, 끝나면 풀리고, 죽은 쪽의 임대는 ttl 뒤에 풀린다 */
class JdbcAccountMaintenanceLeaseDbTest {
    private class Clock(var at: Instant = Instant.parse("2026-10-06T00:00:00.123456Z")) : TimeProvider { override fun now() = at }

    private val clock = Clock()
    private val lease = JdbcAccountMaintenanceLease(AccountDb.jdbc, DbTestDatabase.dialect, clock)

    @BeforeTest fun clean() { AccountDb.jdbc.update("delete from account_locks", emptyMap<String, Any>()) }

    @Test
    fun `thirty-two instances race for one lease and exactly one wins`() {
        val pool = Executors.newFixedThreadPool(32)
        try {
            val go = CountDownLatch(1)
            val results = (1..32).map { pool.submit<Boolean> { go.await(); lease.tryAcquire("purge", Duration.ofMinutes(5)) } }
            go.countDown()
            assertEquals(1, results.count { it.get() })
        } finally { pool.shutdown() }
    }

    @Test
    fun `the lease is per name, released on request and lapses by itself`() {
        assertTrue(lease.tryAcquire("a", Duration.ofMinutes(5)))
        assertTrue(lease.tryAcquire("b", Duration.ofMinutes(5)))
        assertFalse(lease.tryAcquire("a", Duration.ofMinutes(5)))
        lease.release("a")
        assertTrue(lease.tryAcquire("a", Duration.ofMinutes(5)))
        clock.at = clock.at.plus(Duration.ofMinutes(6))
        assertTrue(lease.tryAcquire("b", Duration.ofMinutes(5)), "the holder is gone - the lease lapsed")
    }
}
