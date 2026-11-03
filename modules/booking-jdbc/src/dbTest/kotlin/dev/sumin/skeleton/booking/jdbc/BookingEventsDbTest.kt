package dev.sumin.skeleton.booking.jdbc

import dev.sumin.skeleton.booking.SeatState
import dev.sumin.skeleton.booking.SeatTakenException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 자리 사건 (스펙 §7 시험 7): 바꾼 쪽이 커밋된 뒤에 사건 하나, 롤백된 잡기는 사건 없음, 같은 자리의 v 는 늘 오른다 */
class BookingEventsDbTest {
    private val a = BookingDb.first

    @Test
    fun `7 - every change publishes one event after the commit, a rolled back pick publishes none, and v only grows`() {
        val start = BookingDb.now
        try {
            BookingDb.now = start
            val (pool, seats) = BookingDb.newPool(seats = 6, holdMinutes = 1, maxPerHold = 3)
            val relay = a.relay
            val seen = mutableMapOf<String, Long>()
            // 이 풀의 사건만 본다 - 같은 DB 의 다른 시험이 남긴 보류를 주기 작업이 거두면 다른 풀의 사건도 나온다
            fun mine() = relay.published.filter { it.poolId == pool }
            fun checkNext(expectedSeats: Int, state: SeatState, heldUntil: Boolean) {
                val published = mine().first().also { relay.published.remove(it) }
                assertEquals(expectedSeats, published.changes.size)
                published.changes.forEach { change ->
                    assertEquals(state, change.state)
                    assertEquals(heldUntil, change.heldUntil != null)
                    assertTrue(change.v > (seen[change.seatId] ?: -1), "v of ${change.seatId} must grow: ${change.v} after ${seen[change.seatId]}")
                    seen[change.seatId] = change.v
                }
            }
            relay.published.clear()

            // 잡기 · 놓기 · 모두 놓기
            a.holds.addSeats(pool, "alice", listOf(seats[0], seats[1]))
            assertEquals(1, mine().size); checkNext(2, SeatState.TAKEN, heldUntil = true)
            a.holds.removeSeat(pool, "alice", seats[0])
            assertEquals(1, mine().size); checkNext(1, SeatState.OPEN, heldUntil = false)
            val hold = a.holds.mine("alice").single { it.poolId == pool }
            a.holds.release("alice", hold.id)
            assertEquals(1, mine().size); checkNext(1, SeatState.OPEN, heldUntil = false)

            // 롤백된 잡기: 한 자리는 남이 쥐고 있다 - 같이 고른 빈 자리도 안 잡히고 사건이 없다
            a.holds.addSeats(pool, "alice", listOf(seats[2])); relay.published.clear()
            val before = a.jdbc.queryForObject("select version from booking_seats where id = :s", mapOf("s" to seats[3]), Long::class.java)
            assertFailsWith<SeatTakenException> { a.holds.addSeats(pool, "bob", listOf(seats[3], seats[2])) }
            assertEquals(0, mine().size, "a rolled back pick publishes nothing")
            assertEquals(before, a.jdbc.queryForObject("select version from booking_seats where id = :s", mapOf("s" to seats[3]), Long::class.java))

            // 회수: 시간이 지난 보류를 주기 작업이 거둔다
            BookingDb.now = start.plus(minutes(2))
            assertTrue(a.holds.expireDue() >= 1)
            assertEquals(1, mine().size); checkNext(1, SeatState.OPEN, heldUntil = false)

            // 확정(무료): 팔린 자리는 heldUntil 이 없는 TAKEN
            val carol = a.holds.addSeats(pool, "carol", listOf(seats[4])); relay.published.clear()
            a.holds.confirm("carol", carol.id)
            assertEquals(1, mine().size); checkNext(1, SeatState.TAKEN, heldUntil = false)

            // 막기 · 풀기
            relay.published.clear()
            a.pools.block(pool, listOf(seats[5]))
            assertEquals(1, mine().size); checkNext(1, SeatState.TAKEN, heldUntil = false)
            a.pools.unblock(pool, listOf(seats[5]))
            assertEquals(1, mine().size); checkNext(1, SeatState.OPEN, heldUntil = false)

            assertNull(relay.notYetCommitted.firstOrNull(), "events must come after the commit: ${relay.notYetCommitted}")
            assertNotNull(seen[seats[0]])
            assertInvariants(a.jdbc, pool)
        } finally {
            BookingDb.now = start
        }
    }
}
