package dev.sumin.skeleton.booking.jdbc

import dev.sumin.skeleton.booking.HoldNotFoundException
import dev.sumin.skeleton.booking.HoldStatus
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 이중 판매 0 - 돈 · 동시성이라 두 방언에서 시작 신호를 한꺼번에 여는 걸쇠로 고정한다 (스펙 §7 시험 1 · 2 · 3 · 4).
 * 시험마다 새 풀을 만들어 서로 섞이지 않는다. 끝에는 항상 불변식을 본다.
 */
class BookingConcurrencyDbTest {
    private val a = BookingDb.first
    private val b = BookingDb.second
    private val jdbc: NamedParameterJdbcTemplate = a.jdbc

    private fun count(sql: String, vararg params: Pair<String, Any?>) = jdbc.queryForObject(sql, mapOf(*params), Int::class.java)!!

    private fun noOtherFailure(outcomes: List<Outcome>) =
        assertEquals(emptyList(), outcomes.filterIsInstance<Outcome.Failed>().map { it.error.toString() }, "only Taken / Ok are acceptable")

    // ---- 시험 1 ----------------------------------------------------------------------------------------------------

    private fun oneSeatThousandAccounts(instances: List<BookingInstance>) {
        val (pool, seats) = BookingDb.newPool()
        val outcomes = raceAll(1000) { i ->
            attempt { instances[i % instances.size].holds.addSeats(pool, "acc-$i", listOf(seats[0])).id }
        }
        noOtherFailure(outcomes)
        assertEquals(1, outcomes.count { it is Outcome.Ok }, "exactly one hold gets the seat")
        assertEquals(999, outcomes.count { it == Outcome.Taken })
        val winner = (outcomes.single { it is Outcome.Ok } as Outcome.Ok).holdId
        assertEquals(winner, jdbc.queryForObject("select hold_id from booking_seats where id = :s", mapOf("s" to seats[0]), String::class.java))
        assertEquals(1, count("select count(*) from booking_holds where pool_id = :p", "p" to pool), "a lost race leaves no hold behind")
        assertInvariants(jdbc, pool)
    }

    private fun bundlesOverHundredSeats(instances: List<BookingInstance>) {
        val (pool, seats) = BookingDb.newPool(seats = 100, maxPerHold = 3)
        val random = Random(20261103)
        val asks = (0 until 1000).map { _ -> seats.shuffled(random).take(1 + random.nextInt(3)) }
        val outcomes = raceAll(1000) { i -> attempt { instances[i % instances.size].holds.addSeats(pool, "acc-$i", asks[i]).id } }
        noOtherFailure(outcomes)
        val won = outcomes.count { it is Outcome.Ok }
        assertTrue(won > 0, "somebody wins")
        assertEquals(1000 - won, outcomes.count { it == Outcome.Taken })
        // 어느 자리도 두 보류에 없고(자리 줄은 보류 하나만 가리킨다) 일부만 잡힌 보류가 없다 - 이긴 계정의 묶음은 통째로 그 계정 것이다
        val heldSeats = count("select count(*) from booking_seats where pool_id = :p and status = 'HELD'", "p" to pool)
        val wonSeats = outcomes.withIndex().filter { it.value is Outcome.Ok }.sumOf { asks[it.index].size }
        assertEquals(wonSeats, heldSeats)
        assertEquals(won, count("select count(*) from booking_holds where pool_id = :p", "p" to pool), "no hold for a lost bundle")
        assertEquals(0, count("select count(*) from booking_holds where pool_id = :p and seat_count <> (select count(*) from booking_seats s where s.hold_id = booking_holds.id)", "p" to pool))
        assertInvariants(jdbc, pool)
    }

    @Test
    fun `1a - one seat and a thousand accounts at once - exactly one hold gets it`() = oneSeatThousandAccounts(listOf(a))

    @Test
    fun `1b - a thousand accounts pick one to three of a hundred seats - no seat in two holds and no partial hold`() = bundlesOverHundredSeats(listOf(a))

    // ---- 시험 4 (여러 대) ------------------------------------------------------------------------------------------

    @Test
    fun `4a - the same race split over two independent instances of the same database`() = oneSeatThousandAccounts(listOf(a, b))

    @Test
    fun `4b - the bundle race split over two independent instances`() = bundlesOverHundredSeats(listOf(a, b))

    // ---- 시험 2 ----------------------------------------------------------------------------------------------------

    @Test
    fun `2a - crossed order X,Y against Y,X from 400 accounts - no deadlock and one owner per seat`() {
        val (pool, seats) = BookingDb.newPool(seats = 2, maxPerHold = 2)
        val (x, y) = seats
        val outcomes = raceAll(400) { i ->
            attempt { a.holds.addSeats(pool, "acc-$i", if (i % 2 == 0) listOf(x, y) else listOf(y, x)).id }
        }
        noOtherFailure(outcomes)
        assertEquals(1, outcomes.count { it is Outcome.Ok })
        assertEquals(399, outcomes.count { it == Outcome.Taken })
        assertEquals(1, count("select count(distinct hold_id) from booking_seats where pool_id = :p and status = 'HELD'", "p" to pool), "both seats belong to the same hold")
        assertInvariants(jdbc, pool)
    }

    @Test
    fun `2b - one account picks fifty different seats at once with a limit of two - exactly two and one open hold`() {
        val (pool, seats) = BookingDb.newPool(seats = 50, maxPerHold = 2)
        val outcomes = raceAll(50) { i -> attempt { a.holds.addSeats(pool, "same-account", listOf(seats[i])).id } }
        noOtherFailure(outcomes)
        assertEquals(2, outcomes.count { it is Outcome.Ok })
        assertEquals(48, outcomes.count { it == Outcome.LimitReached })
        assertEquals(1, count("select count(*) from booking_holds where pool_id = :p and status = 'HELD'", "p" to pool))
        assertEquals(2, count("select seat_count from booking_holds where pool_id = :p", "p" to pool))
        assertInvariants(jdbc, pool)
    }

    @Test
    fun `2c - one account presses and releases the same seat a hundred times at once - the end state adds up`() {
        val (pool, seats) = BookingDb.newPool(maxPerHold = 2)
        val outcomes = raceAll(100) { i ->
            try {
                if (i % 2 == 0) a.holds.addSeats(pool, "same-account", listOf(seats[0])) else a.holds.removeSeat(pool, "same-account", seats[0])
                Outcome.Ok("")
            } catch (_: HoldNotFoundException) {
                Outcome.Ok("")   // 아직 보류가 없을 때 온 놓기
            } catch (error: Throwable) {
                Outcome.Failed(error)
            }
        }
        noOtherFailure(outcomes)
        val held = count("select count(*) from booking_seats where id = :s and status = 'HELD'", "s" to seats[0])
        assertEquals(held, count("select coalesce(sum(seat_count), 0) from booking_holds where pool_id = :p and status = 'HELD'", "p" to pool))
        assertEquals(held, count("select coalesce(sum(seats), 0) from booking_usage where pool_id = :p", "p" to pool))
        assertInvariants(jdbc, pool)
    }

    // ---- 시험 3 (결제 빼고) ----------------------------------------------------------------------------------------

    @Test
    fun `3 - two reclaims, the owner letting go and a newcomer on the same expired seat - the seat is freed once`() {
        val start = BookingDb.now
        try {
            repeat(15) { round ->
                BookingDb.now = start
                val (pool, seats) = BookingDb.newPool(holdMinutes = 1)
                val seat = seats[0]
                val holdId = a.holds.addSeats(pool, "owner", listOf(seat)).id
                BookingDb.now = start.plus(minutes(2))   // 보류 시간이 지났다 - 아직 회수 전

                val outcomes = raceAll(4) { i ->
                    when (i) {
                        0, 1 -> attempt { a.holds.expireDue().toString() }
                        2 -> attempt { a.holds.release("owner", holdId).id }
                        else -> attempt { b.holds.addSeats(pool, "newcomer", listOf(seat)).id }
                    }
                }
                noOtherFailure(outcomes)
                assertTrue(outcomes[3] is Outcome.Ok, "round $round: the newcomer takes the freed seat: $outcomes")
                // 풀리고(+1) 다시 잡혔다(+1): 처음 잡힘 v1 → 풀림 v2 → 새 보류 v3. 두 번 풀렸다면 v4 이상이거나 사용량 검사가 먼저 터진다
                assertEquals(3L, jdbc.queryForObject("select version from booking_seats where id = :s", mapOf("s" to seat), Long::class.java), "round $round: freed exactly once")
                assertEquals("HELD", jdbc.queryForObject("select status from booking_seats where id = :s", mapOf("s" to seat), String::class.java))
                assertEquals(0, count("select seats from booking_usage where pool_id = :p and account_id = 'owner'", "p" to pool))
                val ownerStatus = jdbc.queryForObject("select status from booking_holds where id = :h", mapOf("h" to holdId), String::class.java)
                assertTrue(ownerStatus in setOf(HoldStatus.EXPIRED.name, HoldStatus.RELEASED.name), "round $round: owner hold is $ownerStatus")
                assertInvariants(jdbc, pool)
            }
        } finally {
            BookingDb.now = start
        }
    }
}
