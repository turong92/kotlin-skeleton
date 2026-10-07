package dev.sumin.skeleton.account

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** I8 — 임대는 가져간 쪽만 풀 수 있다: 늦게 끝난 옛 보유자의 해제가 그 사이 새로 가져간 쪽의 임대를 풀지 않는다 */
class AccountMaintenanceLeaseTest {
    private val time = MutableTime()
    private val lease = InMemoryAccountMaintenanceLease(time)
    private val ttl = Duration.ofMinutes(5)

    @Test
    fun `acquiring hands out an owner token, a second taker gets nothing while it is held`() {
        assertNotNull(lease.tryAcquire("purge", ttl))
        assertNull(lease.tryAcquire("purge", ttl))
        assertNotNull(lease.tryAcquire("other", ttl), "per name")
    }

    @Test
    fun `the holder releases with its own token and the lease is free again`() {
        val mine = lease.tryAcquire("purge", ttl)!!
        lease.release("purge", mine)
        assertNotNull(lease.tryAcquire("purge", ttl))
    }

    @Test
    fun `a holder whose lease lapsed cannot release the lease the next holder took`() {
        val first = lease.tryAcquire("purge", ttl)!!
        time.advance(Duration.ofMinutes(6))                 // the first run is still going, but its lease lapsed ...
        val second = lease.tryAcquire("purge", ttl)!!       // ... another instance took it ...
        assertNotEquals(first, second)
        lease.release("purge", first)                       // ... and the slow one finishes now
        assertNull(lease.tryAcquire("purge", ttl), "the second holder still holds it")
        lease.release("purge", second)
        assertNotNull(lease.tryAcquire("purge", ttl))
    }

    @Test
    fun `releasing a name nobody holds is harmless`() {
        lease.release("nobody", "token")
        assertNotNull(lease.tryAcquire("nobody", ttl))
    }
}
