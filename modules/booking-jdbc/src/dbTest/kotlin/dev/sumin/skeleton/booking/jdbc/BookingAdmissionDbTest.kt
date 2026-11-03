package dev.sumin.skeleton.booking.jdbc

import dev.sumin.skeleton.booking.BookingHolds
import dev.sumin.skeleton.booking.HoldStatus
import dev.sumin.skeleton.booking.WaitingRoomBookingAdmission
import dev.sumin.skeleton.waitingroom.EntryRequiredException
import dev.sumin.skeleton.waitingroom.WaitingRoomStore
import dev.sumin.skeleton.waitingroom.WaitingRoomTokens
import dev.sumin.skeleton.waitingroom.WaitingRooms
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 대기열과 이음 (스펙 §7 대기열 줄): 입장 시간이 지난 계정은 자리를 더하지 못하고, 이미 잡고 있던 보류는 확정된다 */
class BookingAdmissionDbTest {
    private val a = BookingDb.first

    /** 입장권 검사는 서명만 본다 - 저장소를 읽으면 이 가짜가 시험을 깨뜨린다 */
    private val rooms = WaitingRooms(
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WaitingRoomStore::class.java)) { _, method, _ -> error("the entry check must not read the store: ${method.name}") } as WaitingRoomStore,
        WaitingRoomTokens("a-long-and-varied-test-signing-key-0123456789"),
        BookingDb.time,
    )
    private val holds = BookingHolds(a.store, a.relay, WaitingRoomBookingAdmission(rooms), BookingDb.time)
    private val tokens = WaitingRoomTokens("a-long-and-varied-test-signing-key-0123456789")

    @Test
    fun `an account whose entry time is over cannot add seats, but the hold it already has can still be confirmed`() {
        val start = BookingDb.now
        try {
            BookingDb.now = start
            val (pool, seats) = BookingDb.newPool(seats = 2, maxPerHold = 2, holdMinutes = 30, waitingRoomId = "wr_test")
            val entry = tokens.entry("wr_test", "alice", start.plus(minutes(10)))

            val hold = holds.addSeats(pool, "alice", listOf(seats[0]), entry)
            assertFailsWith<EntryRequiredException> { holds.addSeats(pool, "alice", listOf(seats[1]), null) }
            assertFailsWith<EntryRequiredException> { holds.addSeats(pool, "bob", listOf(seats[1]), entry) }   // 남의 입장권

            BookingDb.now = start.plus(minutes(11))   // 입장 시간은 끝났고 보류(30분)는 아직 산다
            assertFailsWith<EntryRequiredException> { holds.addSeats(pool, "alice", listOf(seats[1]), entry) }
            assertEquals(HoldStatus.CONFIRMED, holds.confirm("alice", hold.id).status)
            assertInvariants(a.jdbc, pool)
        } finally {
            BookingDb.now = start
        }
    }
}
