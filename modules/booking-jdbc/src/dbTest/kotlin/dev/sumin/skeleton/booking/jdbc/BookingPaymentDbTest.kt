package dev.sumin.skeleton.booking.jdbc

import dev.sumin.skeleton.booking.HoldStatus
import dev.sumin.skeleton.booking.OrderOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 결제 연결의 돈 · 동시성 (스펙 §7 시험 3 결제분): 늦은 결제 · 같은 보류에 주문 둘 · 결제 시작 뒤 자리 바꾸기 · 회수 ∥ 늦은 결제.
 * 결제 모듈은 부르지 않는다 - 주문이 `PAID` 가 되어 `onPaid` 가 불리는 순간을 `settlePaid` 로 바로 부른다. 환불 작업은 기록 큐가 받는다.
 */
class BookingPaymentDbTest {
    private val a = BookingDb.first
    private val b = BookingDb.second
    private val jdbc: NamedParameterJdbcTemplate = a.jdbc
    private var orders = 0

    private fun newOrder() = "ord_${System.nanoTime()}_${orders++}"

    private fun status(holdId: String) = jdbc.queryForObject("select status from booking_holds where id = :h", mapOf("h" to holdId), String::class.java)!!
    private fun seatStatus(seatId: String) = jdbc.queryForObject("select status from booking_seats where id = :s", mapOf("s" to seatId), String::class.java)!!

    private fun checkout(pool: String, account: String, seat: String): Pair<String, Long> {
        val hold = a.holds.addSeats(pool, account, listOf(seat))
        return hold.id to checkNotNull(a.holds.priceHold(account, hold.id).total)
    }

    @Test
    fun `3 - a payment that arrives after the hold lapsed re-takes the seats, or is refunded once when somebody else took them`() {
        val start = BookingDb.now
        try {
            BookingDb.now = start
            // 늦게 왔지만 자리가 비어 있다 - 다시 잡아 확정한다
            val (pool, seats) = BookingDb.newPool(seats = 2, holdMinutes = 1, price = 5000)
            val (freeHold, amount) = checkout(pool, "late-1", seats[0])
            BookingDb.now = start.plus(minutes(2))
            a.holds.expireDue()
            val paidLate = newOrder()
            assertEquals(OrderOutcome.CONFIRMED, a.holds.settlePaid(paidLate, "late-1", freeHold, amount, "KRW").outcome)
            assertEquals("CONFIRMED", status(freeHold)); assertEquals("SOLD", seatStatus(seats[0]))
            assertEquals(emptyList(), a.refundQueue.forOrder(paidLate))

            // 늦게 왔고 그사이 다른 사람이 잡았다 - 보류는 UNFULFILLED, 환불 작업은 하나(두 번 불려도 하나)
            BookingDb.now = start
            val (pool2, seats2) = BookingDb.newPool(seats = 1, holdMinutes = 1, price = 5000)
            val (lostHold, lostAmount) = checkout(pool2, "late-2", seats2[0])
            BookingDb.now = start.plus(minutes(2))
            a.holds.expireDue()
            a.holds.addSeats(pool2, "newcomer", listOf(seats2[0]))
            val lateOrder = newOrder()
            repeat(2) { assertEquals(OrderOutcome.UNFULFILLED, b.holds.settlePaid(lateOrder, "late-2", lostHold, lostAmount, "KRW").outcome) }
            assertEquals("UNFULFILLED", status(lostHold))
            assertEquals("HELD", seatStatus(seats2[0]))
            assertEquals(1, a.refundQueue.forOrder(lateOrder).size, "one refund job however many times onPaid is called")
            assertInvariants(jdbc, pool); assertInvariants(jdbc, pool2)
        } finally {
            BookingDb.now = start
        }
    }

    @Test
    fun `3 - two orders paid for the same hold - one confirms it and the other is refunded`() {
        repeat(10) { round ->
            val (pool, seats) = BookingDb.newPool(seats = 1, price = 7000)
            val (hold, amount) = checkout(pool, "double-payer", seats[0])
            val orderIds = listOf(newOrder(), newOrder())
            val outcomes = raceAll(2) { i -> (if (i == 0) a else b).holds.settlePaid(orderIds[i], "double-payer", hold, amount, "KRW").outcome }
            assertEquals(listOf(OrderOutcome.CONFIRMED, OrderOutcome.DUPLICATE), outcomes.sortedBy { it.ordinal }, "round $round")
            val confirmedBy = jdbc.queryForObject("select confirmed_order_id from booking_holds where id = :h", mapOf("h" to hold), String::class.java)
            val loser = orderIds.single { it != confirmedBy }
            assertEquals(1, a.refundQueue.jobs.count { it.first in orderIds }, "round $round: exactly one refund job")
            assertEquals(1, a.refundQueue.forOrder(loser).size)
            assertEquals("SOLD", seatStatus(seats[0]))
            assertInvariants(jdbc, pool)
        }
    }

    @Test
    fun `3 - pricing a hold changes nothing, and a seat changed after the quote is refunded instead of confirmed`() {
        val (pool, seats) = BookingDb.newPool(seats = 3, maxPerHold = 3, price = 1000)
        val hold = a.holds.addSeats(pool, "quoter", listOf(seats[0]))
        val quoted = checkNotNull(a.holds.priceHold("quoter", hold.id).total)
        a.holds.priceHold("quoter", hold.id)   // 견적을 몇 번 불러도
        // 견적은 자리를 묶지 않는다: 더하기 · 빼기가 그대로 된다
        a.holds.addSeats(pool, "quoter", listOf(seats[1]))
        assertEquals(2, a.jdbc.queryForObject("select seat_count from booking_holds where id = :h", mapOf("h" to hold.id), Int::class.java))
        // 견적(1000원) 뒤 자리를 바꿔 2000원이 된 보류에 옛 금액이 결제됐다 - 돈만 내고 자리가 안 맞는 일 없이 환불
        val stale = newOrder()
        assertEquals(OrderOutcome.UNFULFILLED, a.holds.settlePaid(stale, "quoter", hold.id, quoted, "KRW").outcome)
        assertEquals("HELD", status(hold.id))
        assertEquals(1, a.refundQueue.forOrder(stale).size)
        // 지금 값으로 다시 결제하면 확정
        val fresh = newOrder()
        assertEquals(OrderOutcome.CONFIRMED, a.holds.settlePaid(fresh, "quoter", hold.id, checkNotNull(a.holds.priceHold("quoter", hold.id).total), "KRW").outcome)
        assertEquals("SOLD", seatStatus(seats[1]))
        assertInvariants(jdbc, pool)
    }

    @Test
    fun `3 - two orders of one account settled at the same time inside an outer transaction (the way payment calls onPaid) do not deadlock`() {
        val template = org.springframework.transaction.support.TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(a.dataSource))
        repeat(10) { round ->
            val (pool, seats) = BookingDb.newPool(seats = 1, price = 7000)
            val (hold, amount) = checkout(pool, "joined-payer", seats[0])
            val orderIds = listOf(newOrder(), newOrder())
            val outcomes = raceAll(2) { i ->
                // 결제 모듈이 주문 줄 락 트랜잭션 안에서 부르는 모양 - 저장소의 트랜잭션은 이 바깥 트랜잭션에 참여한다
                template.execute { a.holds.settlePaid(orderIds[i], "joined-payer", hold, amount, "KRW").outcome }
            }
            assertEquals(listOf(OrderOutcome.CONFIRMED, OrderOutcome.DUPLICATE), outcomes.sortedBy { it!!.ordinal }, "round $round")
            assertEquals(1, a.refundQueue.jobs.count { it.first in orderIds }, "round $round")
            assertInvariants(jdbc, pool)
        }
    }

    @Test
    fun `3 - reclaims, the owner letting go, a newcomer and a late payment on the same seat - the seat is freed once and goes to one owner`() {
        val start = BookingDb.now
        try {
            repeat(15) { round ->
                BookingDb.now = start
                val (pool, seats) = BookingDb.newPool(holdMinutes = 1, price = 3000)
                val seat = seats[0]
                val (holdId, amount) = checkout(pool, "owner", seat)
                BookingDb.now = start.plus(minutes(2))   // 보류 시간이 지났다 - 아직 회수 전
                val order = newOrder()

                val outcomes = raceAll(5) { i ->
                    when (i) {
                        0, 1 -> attempt { a.holds.expireDue().toString() }
                        2 -> attempt { a.holds.release("owner", holdId).id }
                        3 -> attempt { b.holds.addSeats(pool, "newcomer", listOf(seat)).id }
                        else -> attempt { b.holds.settlePaid(order, "owner", holdId, amount, "KRW").outcome.name }
                    }
                }
                assertEquals(emptyList(), outcomes.filterIsInstance<Outcome.Failed>().map { it.error.toString() }, "round $round")
                val paid = (outcomes[4] as Outcome.Ok).holdId
                val ownerStatus = status(holdId)
                if (paid == OrderOutcome.CONFIRMED.name) {
                    assertEquals(HoldStatus.CONFIRMED.name, ownerStatus, "round $round")
                    assertEquals("SOLD", seatStatus(seat), "round $round")
                    assertEquals(0, a.refundQueue.forOrder(order).size)
                    assertEquals(Outcome.Taken, outcomes[3], "round $round: the seat is the payer's")
                } else {
                    assertEquals(HoldStatus.UNFULFILLED.name, ownerStatus, "round $round")
                    assertTrue(outcomes[3] is Outcome.Ok, "round $round: the newcomer holds the seat the payer lost")
                    assertEquals(1, a.refundQueue.forOrder(order).size, "round $round")
                }
                assertInvariants(jdbc, pool)
            }
        } finally {
            BookingDb.now = start
        }
    }
}
