package dev.sumin.skeleton.waitingroom

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 순번표 · 입장권 토큰 — 위조 · 만료 · 다른 계정의 토큰이 입장권 검사를 통과하면 대기열 새치기다 */
class WaitingRoomTokensTest {
    private val t0 = Instant.parse("2026-11-03T12:00:00Z")
    private var now = t0
    private val time = TimeProvider { now }
    private val keyA = "alpha-secret-with-enough-length-1"
    private val keyB = "bravo-secret-with-enough-length-2"
    private val tokens = WaitingRoomTokens(keyA)

    // requireEntry 와 순번표 확인은 저장소를 읽지 않는다 - 읽으면 시험이 터진다
    private val noStore = java.lang.reflect.Proxy.newProxyInstance(WaitingRoomStore::class.java.classLoader, arrayOf(WaitingRoomStore::class.java)) { _, method, _ ->
        error("must not read the store: ${method.name}")
    } as WaitingRoomStore
    private val rooms = WaitingRooms(noStore, tokens, time)

    @Test
    fun `a valid entry token passes for its room and account`() {
        val entry = tokens.entry("wr_1", "acct-1", t0.plusSeconds(60))

        rooms.requireEntry("wr_1", "acct-1", entry)
    }

    @Test
    fun `no token or garbage is refused`() {
        for (bad in listOf(null, "", "x", "a.b.c", "a..", "a.${"A".repeat(40)}.${"B".repeat(43)}")) {
            assertFailsWith<EntryRequiredException>(bad.toString()) { rooms.requireEntry("wr_1", "acct-1", bad) }
        }
    }

    @Test
    fun `a forged or edited token is refused`() {
        val entry = tokens.entry("wr_1", "acct-1", t0.plusSeconds(60))
        val (key, payload, mac) = entry.split(".")
        val forgedOther = WaitingRoomTokens("not-the-real-secret-at-all-xxxx").entry("wr_1", "acct-1", t0.plusSeconds(60))
        val edited = "$key.${payload.dropLast(2)}AA.$mac"

        for (bad in listOf(forgedOther, edited, "$key.$payload.${mac.reversed()}")) {
            assertFailsWith<EntryRequiredException> { rooms.requireEntry("wr_1", "acct-1", bad) }
        }
    }

    @Test
    fun `an expired entry token is refused from the expiry moment on`() {
        val entry = tokens.entry("wr_1", "acct-1", t0.plusSeconds(60))

        now = t0.plusSeconds(60).minusMillis(1)
        rooms.requireEntry("wr_1", "acct-1", entry)
        now = t0.plusSeconds(60)
        assertFailsWith<EntryRequiredException> { rooms.requireEntry("wr_1", "acct-1", entry) }
    }

    @Test
    fun `another account or another room cannot use the token`() {
        val entry = tokens.entry("wr_1", "acct-1", t0.plusSeconds(60))

        assertFailsWith<EntryRequiredException> { rooms.requireEntry("wr_1", "acct-2", entry) }
        assertFailsWith<EntryRequiredException> { rooms.requireEntry("wr_2", "acct-1", entry) }
    }

    @Test
    fun `a ticket token is not an entry token`() {
        val ticket = tokens.ticket("wr_1", "acct-1", 1)

        assertFailsWith<EntryRequiredException> { rooms.requireEntry("wr_1", "acct-1", ticket) }
    }

    @Test
    fun `another account or room cannot ask with a ticket that is not theirs`() {
        val ticket = tokens.ticket("wr_1", "acct-1", 7)

        assertFailsWith<TicketInvalidException> { rooms.status("wr_1", "acct-2", ticket) }
        assertFailsWith<TicketInvalidException> { rooms.status("wr_2", "acct-1", ticket) }
        assertFailsWith<TicketInvalidException> { rooms.status("wr_1", "acct-1", tokens.entry("wr_1", "acct-1", t0.plusSeconds(60))) }
        assertFailsWith<TicketInvalidException> { rooms.status("wr_1", "acct-1", null) }
    }

    @Test
    fun `a ticket carries its number and a numberless ticket carries none`() {
        assertEquals(7L, tokens.read(tokens.ticket("wr_1", "acct-1", 7))?.position)
        assertNull(tokens.read(tokens.ticket("wr_1", "acct-1", null))?.position)
    }

    @Test
    fun `rotating keys - the previous key still verifies, only the new key signs`() {
        val old = tokens.entry("wr_1", "acct-1", t0.plusSeconds(60))
        val rotated = WaitingRoomTokens(keyB, previousKey = keyA)

        assertEquals("acct-1", rotated.read(old)?.accountId)
        assertNull(tokens.read(rotated.entry("wr_1", "acct-1", t0.plusSeconds(60))))   // 옛 키만 아는 인스턴스는 새 키 토큰을 모른다
        assertNull(WaitingRoomTokens(keyB).read(old))   // previous-key 를 빼면 옛 토큰은 끝
    }
}
