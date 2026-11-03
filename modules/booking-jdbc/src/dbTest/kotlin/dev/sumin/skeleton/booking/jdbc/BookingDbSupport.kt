package dev.sumin.skeleton.booking.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.booking.AllowAllBookingAdmission
import dev.sumin.skeleton.booking.BookingHolds
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.BookingSeatListener
import dev.sumin.skeleton.booking.BookingSeatRelay
import dev.sumin.skeleton.booking.NewBookingPool
import dev.sumin.skeleton.booking.NewSeat
import dev.sumin.skeleton.booking.SeatChange
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

/** 같은 DB 를 보는 인스턴스 하나(연결 풀 · 저장소 · 서비스) - 둘을 만들면 「여러 대」다 */
class BookingInstance(val dataSource: HikariDataSource, val time: TimeProvider) {
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val store = JdbcBookingStore(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), DbTestDatabase.dialect)
    val relay = RecordingRelay(jdbc)
    val holds = BookingHolds(store, relay, AllowAllBookingAdmission, time)
    val pools = BookingPools(store, relay, time)
}

/** 중계에 들어온 것을 기록하고, **부르는 순간 다른 연결에서 DB 를 읽어** 변화가 이미 커밋돼 있는지 본다 */
class RecordingRelay(private val observer: NamedParameterJdbcTemplate) : BookingSeatRelay {
    class Published(val poolId: String, val changes: List<SeatChange>)

    val published = CopyOnWriteArrayList<Published>()
    val notYetCommitted = CopyOnWriteArrayList<String>()

    override fun publish(poolId: String, changes: List<SeatChange>) {
        changes.forEach { change ->
            val version = observer.queryForObject("select version from booking_seats where id = :id", mapOf("id" to change.seatId), Long::class.java)!!
            if (version < change.v) notYetCommitted += "${change.seatId} v${change.v} (db v$version)"
        }
        published += Published(poolId, changes)
    }

    override fun subscribe(poolId: String, listener: BookingSeatListener): AutoCloseable = AutoCloseable { }
}

object BookingDb {
    /** 시험이 옮기는 시계 - 풀마다 id 가 달라 시험끼리 섞이지 않는다 */
    @Volatile var now: Instant = Instant.parse("2026-11-03T03:00:00.123456Z")
    val time = TimeProvider { now }

    private fun pool(): HikariDataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply {
            jdbcUrl = source.url; username = source.username; password = source.password
            maximumPoolSize = 24; connectionTimeout = 120_000
        }
    }

    val first = BookingInstance(pool().also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }, time)

    /** 같은 DB 를 보는 두 번째 인스턴스 (여러 대) */
    val second = BookingInstance(pool(), time)

    fun newPool(
        holdMinutes: Int = 10,
        maxPerHold: Int = 4,
        perAccountLimit: Int? = null,
        seats: Int = 1,
        price: Long? = null,
        instance: BookingInstance = first,
    ): Pair<String, List<String>> {
        val pool = instance.pools.create(
            NewBookingPool("race-${System.nanoTime()}", now.minusSeconds(3600), null, maxPerHold, perAccountLimit, null, holdMinutes),
        )
        val created = instance.pools.addSeats(pool.id, (1..seats).map { NewSeat("A", "1", it, "R", price) })
        return pool.id to created.map { it.id }
    }
}

sealed interface Outcome {
    data class Ok(val holdId: String) : Outcome
    data object Taken : Outcome
    data object LimitReached : Outcome
    data class Failed(val error: Throwable) : Outcome
}

/** [n] 개 일을 가상 스레드로 띄워 시작 신호를 한꺼번에 열고 모두 끝날 때까지 기다린다 */
fun <T> raceAll(n: Int, task: (Int) -> T): List<T> {
    val start = CountDownLatch(1)
    Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        val futures = (0 until n).map { i -> executor.submit<T> { start.await(); task(i) } }
        start.countDown()
        return futures.map { it.get(180, TimeUnit.SECONDS) }
    }
}

fun attempt(action: () -> String): Outcome = try {
    Outcome.Ok(action())
} catch (_: dev.sumin.skeleton.booking.SeatTakenException) {
    Outcome.Taken
} catch (_: dev.sumin.skeleton.booking.LimitReachedException) {
    Outcome.LimitReached
} catch (error: Throwable) {
    Outcome.Failed(error)
}

/** 불변식(스펙 §3) - 모든 시험이 끝에 확인한다. 어긴 건수를 이름과 함께 모아 한꺼번에 보여 준다 */
fun assertInvariants(jdbc: NamedParameterJdbcTemplate, poolId: String) {
    val checks = linkedMapOf(
        "HELD seat points at a HELD hold and has a deadline" to
            "select count(*) from booking_seats s left join booking_holds h on h.id = s.hold_id where s.pool_id = :p and s.status = 'HELD' and (h.id is null or h.status <> 'HELD' or s.held_until is null)",
        "SOLD seat points at a CONFIRMED hold" to
            "select count(*) from booking_seats s left join booking_holds h on h.id = s.hold_id where s.pool_id = :p and s.status = 'SOLD' and (h.id is null or h.status <> 'CONFIRMED')",
        "AVAILABLE / BLOCKED seat has no hold" to
            "select count(*) from booking_seats s where s.pool_id = :p and s.status in ('AVAILABLE', 'BLOCKED') and (s.hold_id is not null or s.held_until is not null)",
        "open hold: seat_count = seats pointing at it = hold_seats rows" to
            "select count(*) from booking_holds h where h.pool_id = :p and h.status in ('HELD', 'CONFIRMED') and " +
            "(h.seat_count <> (select count(*) from booking_seats s where s.hold_id = h.id) or h.seat_count <> (select count(*) from booking_hold_seats x where x.hold_id = h.id))",
        "closed hold owns no seat" to
            "select count(*) from booking_seats s join booking_holds h on h.id = s.hold_id where s.pool_id = :p and h.status not in ('HELD', 'CONFIRMED')",
        "usage = sum of the account's open / confirmed seat_count" to
            "select count(*) from booking_usage u where u.pool_id = :p and u.seats <> coalesce((select sum(h.seat_count) from booking_holds h where h.pool_id = u.pool_id and h.account_id = u.account_id and h.status in ('HELD', 'CONFIRMED')), 0)",
        "active_slot is set exactly while HELD" to
            "select count(*) from booking_holds h where h.pool_id = :p and ((h.status = 'HELD' and h.active_slot is null) or (h.status <> 'HELD' and h.active_slot is not null))",
    )
    val violations = checks.mapValues { (_, sql) -> jdbc.queryForObject(sql, mapOf("p" to poolId), Int::class.java)!! }.filterValues { it != 0 }
    assertEquals(emptyMap(), violations, "invariants violated in $poolId")
}

fun minutes(m: Long): Duration = Duration.ofMinutes(m)
