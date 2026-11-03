package dev.sumin.skeleton.waitingroom.jdbc

import dev.sumin.skeleton.waitingroom.QueueState
import dev.sumin.skeleton.waitingroom.QueueStatus
import dev.sumin.skeleton.waitingroom.WaitingRoom
import dev.sumin.skeleton.waitingroom.EntryRequiredException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 대기열(가짜 시계) - 두 방언 같은 시험. 시계는 `WaitingDb.now` 를 옮긴다 */
class WaitingRoomDbTest {
    private val rooms = WaitingDb.rooms

    /** 오픈 전 줄에 [n] 명이 선다 - 계정마다 번호 없는 순번표 토큰 */
    private fun enqueue(room: WaitingRoom, n: Int, prefix: String = "acct"): Map<String, String> =
        raceAll(n) { i -> "$prefix-$i" to checkNotNull(rooms.issueTicket(room.id, "$prefix-$i").ticket) }.toMap()

    private fun askAll(room: WaitingRoom, tickets: Map<String, String>): Map<String, QueueStatus> =
        tickets.entries.toList().let { entries -> raceAll(entries.size) { i -> entries[i] } }.associate { (account, ticket) -> account to rooms.status(room.id, account, ticket) }

    @Test
    fun `at 100 per minute exactly 100 are let in 60 seconds after the opening and not one before`() {
        val room = WaitingDb.newRoom(perMinute = 100)
        val tickets = enqueue(room, 250)

        WaitingDb.now = room.opensAt.plusSeconds(60).minusMillis(1)
        val before = askAll(room, tickets)
        WaitingDb.now = room.opensAt.plusSeconds(60)
        val after = askAll(room, tickets)

        assertEquals(99, before.values.count { it.state == QueueState.ADMITTED })
        val admitted = after.filterValues { it.state == QueueState.ADMITTED }
        assertEquals(100, admitted.size)
        assertEquals((1L..100L).toList(), admitted.values.map { it.position!! }.sorted())
        assertEquals(150, after.values.count { it.state == QueueState.WAITING })
        // 입장한 사람의 입장권은 통하고, 아직 기다리는 사람은 입장권이 없다
        admitted.forEach { (account, status) -> rooms.requireEntry(room.id, account, status.entryToken) }
        assertTrue(after.values.filter { it.state == QueueState.WAITING }.all { it.entryToken == null })
    }

    @Test
    fun `a pause lets nobody in and the count resumes after it`() {
        val room = WaitingDb.newRoom(perMinute = 100)
        val tickets = enqueue(room, 250)

        WaitingDb.now = room.opensAt.plusSeconds(30)
        rooms.setRate(room.id, 0)
        WaitingDb.now = room.opensAt.plusSeconds(90)
        assertEquals(50, askAll(room, tickets).values.count { it.state == QueueState.ADMITTED })   // 멈춘 동안 늘지 않는다
        assertEquals(null, askAll(room, tickets).values.first { it.state == QueueState.WAITING }.estimatedWaitSeconds)   // 멈춤이면 예상 시간이 없다
        WaitingDb.now = room.opensAt.plusSeconds(120)
        rooms.setRate(room.id, 100)
        WaitingDb.now = room.opensAt.plusSeconds(150)

        assertEquals(100, askAll(room, tickets).values.count { it.state == QueueState.ADMITTED })   // 50 + 30초 x 100/분
    }

    @Test
    fun `200 requests at the opening shuffle the line once and number everyone 1 to n without a gap`() {
        val room = WaitingDb.newRoom()
        val arrivals = (0 until 200).map { "acct-$it" }
        val tickets = arrivals.associateWith { checkNotNull(rooms.issueTicket(room.id, it).ticket) }   // 번호 없이, 도착 순서대로
        assertEquals(List(200) { null }, WaitingDb.jdbc.queryForList("select queue_no from waiting_room_tickets where room_id = :id", mapOf("id" to room.id), Long::class.javaObjectType))

        WaitingDb.now = room.opensAt
        val winsBefore = WaitingDb.store.shuffleWins.get()
        val answers = askAll(room, tickets)

        assertEquals(1, WaitingDb.store.shuffleWins.get() - winsBefore)
        assertEquals((1L..200L).toList(), WaitingDb.ticketNumbers(room.id))
        assertEquals((1L..200L).toList(), answers.values.map { it.position!! }.sorted())
        assertNotNull(WaitingDb.store.findRoom(room.id)!!.shuffledAt)
        assertEquals(200L, WaitingDb.store.findRoom(room.id)!!.queueTail)
        // 도착 순서가 아니라 섞인 순서다
        assertNotEquals(arrivals, arrivals.sortedBy { answers.getValue(it).position })
        // 번호가 알려진 사람은 번호가 든 새 순번표를 받고, 그 뒤로는 DB 없이 같은 답을 받는다
        assertTrue(answers.values.all { it.ticket != null })
    }

    @Test
    fun `of 200 simultaneous shuffle calls exactly one wins and the numbers are a permutation of 1 to n`() {
        val room = WaitingDb.newRoom()
        enqueue(room, 200)
        WaitingDb.now = room.opensAt

        val wins = raceAll(200) { WaitingDb.store.shuffle(room.id, WaitingDb.now) }

        assertEquals(1, wins.count { it })
        assertEquals((1L..200L).toList(), WaitingDb.ticketNumbers(room.id))
        assertEquals(200L, WaitingDb.store.findRoom(room.id)!!.queueTail)
    }

    @Test
    fun `the same account holds one ticket however many requests race, before and after the opening`() {
        val room = WaitingDb.newRoom()

        raceAll(400) { rooms.issueTicket(room.id, "acct-${it % 200}") }
        assertEquals(200, WaitingDb.ticketCount(room.id))

        WaitingDb.now = room.opensAt
        val late = raceAll(300) { i -> "late-${i % 150}" to rooms.issueTicket(room.id, "late-${i % 150}").position!! }

        assertEquals(350, WaitingDb.ticketCount(room.id))
        assertEquals((1L..350L).toList(), WaitingDb.ticketNumbers(room.id))   // 오픈 뒤 온 사람은 온 순서로 201.. 이어서, 빈틈 없이
        assertEquals((201L..350L).toList(), late.map { it.second }.distinct().sorted())
        late.groupBy({ it.first }, { it.second }).forEach { (_, numbers) -> assertEquals(1, numbers.distinct().size) }   // 같은 계정은 늘 같은 번호
    }

    @Test
    fun `a number whose entry time ran out goes to the back, and a numberless late ticket is numbered at the back`() {
        val room = WaitingDb.newRoom(perMinute = 60, entryMinutes = 1)
        WaitingDb.now = room.opensAt
        val first = rooms.issueTicket(room.id, "first")
        assertEquals(1L, first.position)

        WaitingDb.now = room.opensAt.plusSeconds(1)   // 번호 1 입장 - 1초에 1명
        val admitted = rooms.status(room.id, "first", first.ticket)
        assertEquals(QueueState.ADMITTED, admitted.state)
        rooms.requireEntry(room.id, "first", admitted.entryToken)
        assertEquals(1L, rooms.issueTicket(room.id, "first").position)   // 입장 시간 안에는 같은 순번표

        // 섞기와 겹쳐 번호 없이 커밋된 늦은 줄 - 맨 뒤 번호로 거둔다
        WaitingDb.store.addPrequeueTicket(room.id, "straggler", WaitingDb.now)
        assertEquals(2L, rooms.issueTicket(room.id, "straggler").position)

        WaitingDb.now = room.opensAt.plusSeconds(1).plus(java.time.Duration.ofMinutes(1))   // 입장 시간 끝
        assertEquals(QueueState.EXPIRED, rooms.status(room.id, "first", first.ticket).state)
        assertFailsWith<EntryRequiredException> { rooms.requireEntry(room.id, "first", admitted.entryToken) }
        val again = rooms.issueTicket(room.id, "first")
        assertEquals(3L, again.position)   // 맨 뒤 - 옛 번호 1 은 비고, 번호는 늘 겹치지 않는다
        assertEquals(listOf(2L, 3L), WaitingDb.ticketNumbers(room.id))
    }
}
