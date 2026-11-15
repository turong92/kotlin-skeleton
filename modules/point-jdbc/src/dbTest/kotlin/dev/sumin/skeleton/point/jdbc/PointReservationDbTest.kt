package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.point.PointCancelExceededException
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointInsufficientException
import dev.sumin.skeleton.point.PointReservationExceededException
import dev.sumin.skeleton.point.PointReservationNotFoundException
import dev.sumin.skeleton.point.PointReservationNotHeldException
import dev.sumin.skeleton.point.PointReservationStatus
import dev.sumin.skeleton.point.PointSourceConflictException
import dev.sumin.skeleton.point.PointWallet
import dev.sumin.skeleton.point.StorePointWallet
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 잡아두기(reserve) · 확정(capture) · 풀기(release): 돈이 걸린 동작 - 잡은 만큼 못 쓰고, 두 번 빠지지 않고, 공짜가 되지 않는다 */
class PointReservationDbTest {
    private val wallet: PointWallet = StorePointWallet(PointDb.store, PointDb.time, holdTimeout = HOLD)
    private val jdbc = PointDb.jdbc
    private val now get() = PointDb.now

    @BeforeTest
    fun clean() {
        PointDb.now = Instant.parse("2026-10-28T03:00:00.123456Z")
        listOf(
            "point_reservations", "point_spend_allocations", "point_restore_links", "point_operation_attempts", "point_operations",
            "point_entries", "point_balances",
        ).forEach { jdbc.update("delete from $it", emptyMap<String, Any>()) }
    }

    private fun remaining(id: String) = jdbc.queryForObject("select remaining from point_entries where id = :id", mapOf("id" to id), Long::class.java)
    private fun entryCount(account: String) = jdbc.queryForObject("select count(*) from point_entries where account_id = :a", mapOf("a" to account), Long::class.java)

    @Test
    fun `a reservation lowers what can be spent and leaves no ledger line`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")

        val held = wallet.reserve("a1", 40, "GEN", "r1")

        assertEquals(PointReservationStatus.HELD, held.status)
        assertEquals(now.plus(HOLD), held.holdUntil)
        val balance = wallet.balance("a1")
        assertEquals(60, balance.balance)
        assertEquals(40, balance.held)
        assertEquals(1, entryCount("a1"))   // 잡기는 내역에 줄을 만들지 않는다
        assertFailsWith<PointInsufficientException> { wallet.spend("a1", 61, "ORDER", "o1") }
        assertFailsWith<PointInsufficientException> { wallet.reserve("a1", 61, "GEN", "r2") }
        wallet.spend("a1", 60, "ORDER", "o2")
        assertEquals(0, wallet.balance("a1").balance)
    }

    @Test
    fun `reserving the same key again returns the same hold, a different request is a conflict`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")

        val first = wallet.reserve("a1", 40, "GEN", "r1")
        val again = wallet.reserve("a1", 40, "GEN", "r1")

        assertEquals(first.id, again.id)
        assertEquals(40, wallet.balance("a1").held)
        assertFailsWith<PointSourceConflictException> { wallet.reserve("a1", 41, "GEN", "r1") }
        assertFailsWith<PointSourceConflictException> { wallet.reserve("a1", 40, "GRANT", "g1") }   // 원장 줄이 쓴 키
        assertEquals(40, wallet.balance("a1").held)
    }

    @Test
    fun `capture refuses when the ledger took the key meanwhile, and the hold stays releasable`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.reserve("a1", 40, "GEN", "r1")
        wallet.spend("a1", 10, "GEN", "r1")   // 앱이 잘못 같은 키를 원장에 썼다

        assertFailsWith<PointSourceConflictException> { wallet.capture("GEN", "r1") }
        assertEquals(40, wallet.balance("a1").held)

        wallet.release("GEN", "r1")
        assertEquals(90, wallet.balance("a1").balance)   // 바로 쓴 10 만 빠졌다 - 두 번 빠지지 않는다
    }

    @Test
    fun `a failed reservation changes nothing`() {
        wallet.earn("a1", 50, null, "GRANT", "g1")

        assertFailsWith<PointInsufficientException> { wallet.reserve("a1", 51, "GEN", "r1") }

        assertNull(wallet.reservationOf("GEN", "r1"))
        assertEquals(50, wallet.balance("a1").balance)
    }

    @Test
    fun `concurrent reservations cannot hold more than the balance`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")

        val results = race(8) { i -> runCatching { wallet.reserve("a1", 60, "GEN", "r$i") } }

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is PointInsufficientException })
        assertEquals(60, wallet.balance("a1").held)
        assertEquals(40, wallet.balance("a1").balance)
    }

    @Test
    fun `capture writes one deduction and is idempotent`() {
        val bundle = wallet.earn("a1", 100, null, "GRANT", "g1")!!
        wallet.reserve("a1", 40, "GEN", "r1")

        val spent = wallet.capture("GEN", "r1")
        val again = wallet.capture("GEN", "r1")

        assertEquals(spent.id, again.id)
        assertEquals(PointEntryKind.SPEND, spent.kind)
        assertEquals(-40, spent.delta)
        assertEquals(spent, wallet.entryOf("GEN", "r1"))
        assertEquals(PointReservationStatus.CAPTURED, wallet.reservationOf("GEN", "r1")!!.status)
        assertEquals(60, wallet.balance("a1").balance)
        assertEquals(0, wallet.balance("a1").held)
        assertEquals(60, remaining(bundle.id))
        assertEquals(2, entryCount("a1"))
        assertEquals(40, wallet.cancelSpend("a1", "GEN", "r1", null, "GEN_CANCEL", "c1").delta)   // 확정된 차감은 사용 취소(환원)가 된다
        assertEquals(100, wallet.balance("a1").balance)
    }

    @Test
    fun `a partial capture deducts only what was used and frees the rest`() {
        val soon = wallet.earn("a1", 30, now.plus(Duration.ofDays(5)), "GRANT", "g-soon")!!
        val late = wallet.earn("a1", 70, now.plus(Duration.ofDays(50)), "GRANT", "g-late")!!
        wallet.reserve("a1", 60, "GEN", "r1")   // 소멸이 가까운 묶음부터: soon 30 + late 30

        val spent = wallet.capture("GEN", "r1", 25)

        assertEquals(-25, spent.delta)
        assertEquals(PointEntryKind.SPEND, spent.kind)
        assertEquals(75, wallet.balance("a1").balance)
        assertEquals(0, wallet.balance("a1").held)
        assertEquals(5, remaining(soon.id))        // 쓴 25 는 먼저 잡힌 soon 에서
        assertEquals(70, remaining(late.id))       // 남은 35 중 30 은 late 로, 5 는 soon 으로 돌아간다
        assertEquals(25, wallet.reservationOf("GEN", "r1")!!.capturedAmount)
        assertEquals(25, wallet.cancelSpend("a1", "GEN", "r1", null, "GEN_CANCEL", "c1").delta)   // 할당이 쓴 만큼만 남았다
        assertFailsWith<PointCancelExceededException> { wallet.cancelSpend("a1", "GEN", "r1", 1, "GEN_CANCEL", "c2") }
    }

    @Test
    fun `capture cannot take more than was held and a repeat with another amount is a conflict`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.reserve("a1", 40, "GEN", "r1")

        assertFailsWith<PointReservationExceededException> { wallet.capture("GEN", "r1", 41) }
        assertFailsWith<IllegalArgumentException> { wallet.capture("GEN", "r1", 0) }
        assertEquals(40, wallet.balance("a1").held)   // 실패는 아무것도 바꾸지 않는다
        wallet.capture("GEN", "r1", 10)

        assertFailsWith<PointSourceConflictException> { wallet.capture("GEN", "r1", 11) }
        assertEquals(10, wallet.capture("GEN", "r1", 10).let { -it.delta })
        assertEquals(90, wallet.balance("a1").balance)
    }

    @Test
    fun `release gives everything back, is idempotent, and excludes capture`() {
        val bundle = wallet.earn("a1", 100, null, "GRANT", "g1")!!
        wallet.reserve("a1", 40, "GEN", "r1")

        val released = wallet.release("GEN", "r1")
        val again = wallet.release("GEN", "r1")

        assertEquals(PointReservationStatus.RELEASED, released.status)
        assertEquals(released.id, again.id)
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(100, remaining(bundle.id))
        assertEquals(1, entryCount("a1"))
        assertFailsWith<PointReservationNotHeldException> { wallet.capture("GEN", "r1") }
        assertEquals(100, wallet.balance("a1").balance)   // 풀린 건을 확정하려다 실패해도 공짜로 쓰이지 않는다

        wallet.reserve("a1", 10, "GEN", "r2")
        wallet.capture("GEN", "r2")
        assertFailsWith<PointReservationNotHeldException> { wallet.release("GEN", "r2") }
        assertEquals(90, wallet.balance("a1").balance)
        assertFailsWith<PointReservationNotFoundException> { wallet.release("GEN", "nope") }
        assertFailsWith<PointReservationNotFoundException> { wallet.capture("GEN", "nope") }
    }

    @Test
    fun `an abandoned hold is released after its deadline and only then`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.reserve("a1", 40, "GEN", "r1")

        PointDb.now = now.plus(HOLD).minusSeconds(1)
        assertEquals(0, wallet.expireReservations(now))
        assertEquals(60, wallet.balance("a1").balance)

        PointDb.now = now.plusSeconds(1)
        assertEquals(1, wallet.expireReservations(now))
        assertEquals(0, wallet.expireReservations(now))   // 멱등
        assertEquals(PointReservationStatus.EXPIRED, wallet.reservationOf("GEN", "r1")!!.status)
        assertEquals(100, wallet.balance("a1").balance)
        assertEquals(0, wallet.balance("a1").held)
        assertFailsWith<PointReservationNotHeldException> { wallet.capture("GEN", "r1") }
        assertEquals(PointReservationStatus.EXPIRED, wallet.release("GEN", "r1").status)   // 이미 풀린 건 - 다시 풀어도 같은 결과
    }

    @Test
    fun `capture racing the expiry sweep ends one way only - never twice charged, never free`() {
        repeat(12) { round ->
            val account = "race$round"
            wallet.earn(account, 100, null, "GRANT", "g-$round")
            wallet.reserve(account, 40, "GEN", "r-$round")
            PointDb.now = PointDb.now.plus(HOLD).plusSeconds(1)   // 기한 지남 - 아직 훑지 않았다

            val outcomes = race(2) { i ->
                if (i == 0) runCatching { wallet.capture("GEN", "r-$round") } else runCatching { wallet.expireReservations(PointDb.now) }
            }

            val captured = outcomes[0].isSuccess
            val reservation = wallet.reservationOf("GEN", "r-$round")!!
            if (captured) {
                assertEquals(PointReservationStatus.CAPTURED, reservation.status)
                assertEquals(60, wallet.balance(account).balance)
            } else {
                assertTrue(outcomes[0].exceptionOrNull() is PointReservationNotHeldException)
                assertEquals(PointReservationStatus.EXPIRED, reservation.status)
                assertEquals(100, wallet.balance(account).balance)
            }
            assertEquals(0, wallet.balance(account).held)
            assertEquals(if (captured) 1L else 0L, jdbc.queryForObject("select count(*) from point_entries where account_id = :a and kind = 'SPEND'", mapOf("a" to account), Long::class.java))
        }
    }

    @Test
    fun `a held bundle does not expire while held - capture still works, release past the expiry closes it`() {
        val bundle = wallet.earn("a1", 100, now.plus(Duration.ofDays(1)), "GRANT", "g1")!!
        wallet.reserve("a1", 100, "GEN", "keep")
        wallet.earn("a1", 50, now.plus(Duration.ofDays(1)), "GRANT", "g2")
        wallet.reserve("a1", 30, "GEN", "lapse")

        PointDb.now = now.plus(Duration.ofDays(2))   // 묶음의 유효기간이 지났다 (잡기 기한은 따로 - 아직 안 풀었다)
        wallet.expireDue(now)
        assertEquals(-100, wallet.capture("GEN", "keep").delta)   // 잡혀 있던 몫은 소멸이 건드리지 않았다
        assertEquals(0, remaining(bundle.id))

        val released = wallet.release("GEN", "lapse")

        assertEquals(PointReservationStatus.RELEASED, released.status)
        assertEquals(0, wallet.balance("a1").balance)   // 유효기간이 지난 몫은 풀어도 되살아나지 않는다
        fun sum(kind: String) = jdbc.queryForObject("select coalesce(sum(delta), 0) from point_entries where account_id = 'a1' and kind = '$kind'", emptyMap<String, Any>(), Long::class.java)
        assertEquals(-50, sum("EXPIRE"))   // 잡힌 채 지난 30 은 풀 때 소멸 줄로, 안 잡힌 20 은 소멸 작업이
        assertEquals(0L, jdbc.queryForObject("select coalesce(sum(delta), 0) from point_entries where account_id = 'a1'", emptyMap<String, Any>(), Long::class.java))   // 원장 합 = 잔액
    }

    @Test
    fun `erasing the account drops its holds too`() {
        wallet.earn("a1", 100, null, "GRANT", "g1")
        wallet.reserve("a1", 40, "GEN", "r1")
        wallet.reserve("a1", 10, "GEN", "r2")
        wallet.capture("GEN", "r2")

        PointDb.store.eraseAccount("a1")

        assertNull(wallet.reservationOf("GEN", "r1"))
        assertNull(wallet.reservationOf("GEN", "r2"))
        assertEquals(0L, jdbc.queryForObject("select count(*) from point_spend_allocations", emptyMap<String, Any>(), Long::class.java))
    }

    @Test
    fun `reserve is refused when reservations are not switched on`() {
        val off = StorePointWallet(PointDb.store, PointDb.time)
        off.earn("a1", 100, null, "GRANT", "g1")

        assertFailsWith<IllegalStateException> { off.reserve("a1", 10, "GEN", "r1") }
        assertEquals(100, off.balance("a1").balance)
    }

    private fun <T> race(workers: Int, task: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val ready = CountDownLatch(workers)
            val go = CountDownLatch(1)
            val futures = (0 until workers).map { i -> pool.submit<T> { ready.countDown(); go.await(); task(i) } }
            ready.await()
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        val HOLD: Duration = Duration.ofMinutes(30)
    }
}
