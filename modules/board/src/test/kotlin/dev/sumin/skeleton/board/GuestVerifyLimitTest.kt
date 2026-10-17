package dev.sumin.skeleton.board

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 맞는 비밀로도 bcrypt 를 한도 없이 돌릴 수 없다 - IP 별 확인 한도(되돌리지 않음)와 맞은 비밀의 짧은 기억 */
class GuestVerifyLimitTest {
    private class CountingSecrets(private val real: BoardGuestSecrets) : BoardGuestSecrets by real {
        val matchCalls = AtomicInteger()
        override fun matches(raw: String, stored: String): Boolean { matchCalls.incrementAndGet(); return real.matches(raw, stored) }
    }

    private fun guests(secrets: CountingSecrets, ttl: Duration, verifyCapacity: Int = 60): BoardGuests {
        val props = BoardProperties(guest = BoardProperties.Guest(enabled = true, secretVerifications = BoardProperties.AttemptLimit(verifyCapacity, Duration.ofMinutes(1)), verifiedCacheTtl = ttl))
        val store = dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore()
        return BoardGuests(props, secrets, StoreBoardGuestLimiter({ store }, props.guest))
    }

    private val stored = DefaultBoardGuestSecrets(GuestSecretMode.PASSWORD).hash("right-secret")

    private fun hammer(g: BoardGuests, requests: Int): Pair<Int, Int> {
        val ok = AtomicInteger(); val limited = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        repeat(requests) {
            pool.execute {
                try { if (g.verify(GuestContext("right-secret", "ip-1", "1.1.1.1"), "c1", stored)) ok.incrementAndGet() }
                catch (e: BoardException) { if (e.errorCode == BoardErrorCode.RATE_LIMITED) limited.incrementAndGet() }
            }
        }
        pool.shutdown(); assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        return ok.get() to limited.get()
    }

    @Test
    fun `without the memory, correct secrets still cost at most the verification limit worth of hashes`() {
        val secrets = CountingSecrets(DefaultBoardGuestSecrets(GuestSecretMode.PASSWORD))
        val (ok, limited) = hammer(guests(secrets, Duration.ZERO, verifyCapacity = 20), 100)
        assertEquals(20, secrets.matchCalls.get(), "bcrypt ran no more than the limit allows, although every secret was right")
        assertEquals(20, ok); assertEquals(80, limited)
    }

    @Test
    fun `with the memory, a secret that just matched is not hashed again`() {
        val secrets = CountingSecrets(DefaultBoardGuestSecrets(GuestSecretMode.PASSWORD))
        val g = guests(secrets, Duration.ofMinutes(5))
        val first = g.verify(GuestContext("right-secret", "ip-1", "1.1.1.1"), "c1", stored)
        repeat(50) { assertTrue(g.verify(GuestContext("right-secret", "ip-1", "1.1.1.1"), "c1", stored)) }
        assertTrue(first)
        assertEquals(1, secrets.matchCalls.get(), "one hash for 51 requests")
        // 다른 행 · 다른 비밀은 기억에 없다
        assertTrue(!g.verify(GuestContext("wrong-secret", "ip-1", "1.1.1.1"), "c1", stored))
        assertEquals(2, secrets.matchCalls.get())
        g.verify(GuestContext("right-secret", "ip-1", "1.1.1.1"), "c2", stored)
        assertEquals(3, secrets.matchCalls.get(), "another row is hashed on its own")
    }

    @Test
    fun `a flood of correct concurrent requests stays inside the limit`() {
        val secrets = CountingSecrets(DefaultBoardGuestSecrets(GuestSecretMode.PASSWORD))
        hammer(guests(secrets, Duration.ofMinutes(5)), 200)
        assertTrue(secrets.matchCalls.get() <= 60, "at most the verification limit, usually only a few before the first match is remembered: ${secrets.matchCalls.get()}")
    }
}
