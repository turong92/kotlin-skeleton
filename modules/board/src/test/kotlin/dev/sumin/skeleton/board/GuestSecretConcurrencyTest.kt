package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 비밀 대입 한도(스펙 §7): 동시에 몰려도 한도만큼만 해시를 돌린다 - 세고 나서 해시한다. 맞는 시도는 되돌려 한도를 닳게 하지 않는다 */
class GuestSecretConcurrencyTest {
    private val props = BoardProperties(guest = BoardProperties.Guest(enabled = true, verifiedCacheTtl = java.time.Duration.ZERO))   // 맞은 비밀의 기억을 끈다 - 이 시험은 대입 한도를 센다
    private val hashes = AtomicInteger()
    private val real = DefaultBoardGuestSecrets(GuestSecretMode.PASSWORD)
    private val counting = object : BoardGuestSecrets by real {
        override fun matches(raw: String, stored: String): Boolean { hashes.incrementAndGet(); return real.matches(raw, stored) }
    }
    private val store = InMemoryFixedWindowRateLimitStore()
    private val guests = BoardGuests(props, counting, StoreBoardGuestLimiter({ store }, props.guest, MidWindowClock.clock), clock = { MidWindowClock.NOW })
    private val stored = real.hash("right-secret")

    private fun burst(threads: Int, ctx: (Int) -> GuestContext): List<Result<Boolean>> {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { i -> pool.submit<Result<Boolean>> { start.await(); runCatching { guests.verify(ctx(i), "p1", stored) } } }
            start.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `sixty wrong guesses at once from one address hash no more than the limit allows, and the rest are 429`() {
        val results = burst(60) { GuestContext("wrong-secret-$it", "same-ip") }
        assertTrue(hashes.get() <= props.guest.secretAttempts.capacity, "hashed ${hashes.get()} times")
        assertEquals(60 - hashes.get(), results.count { (it.exceptionOrNull() as? BoardException)?.errorCode == BoardErrorCode.RATE_LIMITED })
        assertTrue(results.none { it.getOrNull() == true })
    }

    @Test
    fun `sixty wrong guesses spread over many addresses are still bounded by the row limit`() {
        burst(60) { GuestContext("wrong-secret-$it", "ip-$it") }
        assertTrue(hashes.get() <= props.guest.secretAttempts.capacity, "hashed ${hashes.get()} times")
    }

    @Test
    fun `right secrets give their count back, so many verified calls in a row never lock the owner out`() {
        repeat(40) { assertTrue(guests.verify(GuestContext("right-secret", "owner-ip"), "p1", stored)) }
        assertEquals(40, hashes.get())
    }
}
