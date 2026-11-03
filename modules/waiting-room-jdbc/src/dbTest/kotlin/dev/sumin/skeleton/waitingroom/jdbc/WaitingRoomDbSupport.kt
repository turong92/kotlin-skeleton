package dev.sumin.skeleton.waitingroom.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.waitingroom.NewWaitingRoom
import dev.sumin.skeleton.waitingroom.WaitingRoom
import dev.sumin.skeleton.waitingroom.WaitingRoomStore
import dev.sumin.skeleton.waitingroom.WaitingRoomTokens
import dev.sumin.skeleton.waitingroom.WaitingRooms
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

/** 섞기에서 이긴 호출 수를 센다 - 진짜 저장소에 그대로 맡긴다 */
class CountingStore(private val delegate: WaitingRoomStore) : WaitingRoomStore by delegate {
    val shuffleWins = AtomicInteger()

    override fun shuffle(roomId: String, now: Instant): Boolean = delegate.shuffle(roomId, now).also { if (it) shuffleWins.incrementAndGet() }
}

object WaitingDb {
    /** 시험이 옮기는 시계 - 방마다 id 가 달라 시험끼리 섞이지 않는다 */
    @Volatile var now: Instant = Instant.parse("2026-11-03T03:00:00.123456Z")
    val time = TimeProvider { now }

    private val dataSource: HikariDataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply {
            jdbcUrl = source.url; username = source.username; password = source.password
            maximumPoolSize = 24; connectionTimeout = 120_000
        }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }
    }

    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val store = CountingStore(JdbcWaitingRoomStore(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), DbTestDatabase.dialect))
    val tokens = WaitingRoomTokens("a-test-secret-that-is-long-enough-1")
    val rooms = WaitingRooms(store, tokens, time)

    /** 오픈은 [opensInSeconds] 초 뒤 - 지금은 오픈 전 줄이 서는 때다 */
    fun newRoom(perMinute: Int = 100, opensInSeconds: Long = 600, entryMinutes: Int = 10): WaitingRoom =
        rooms.create(NewWaitingRoom("room-${System.nanoTime()}", now.plusSeconds(opensInSeconds), perMinute, entryMinutes = entryMinutes))

    fun ticketNumbers(roomId: String): List<Long> =
        jdbc.queryForList("select queue_no from waiting_room_tickets where room_id = :id order by queue_no", mapOf("id" to roomId), Long::class.java).map { checkNotNull(it) }

    fun ticketCount(roomId: String): Int =
        jdbc.queryForObject("select count(*) from waiting_room_tickets where room_id = :id", mapOf("id" to roomId), Int::class.java)!!
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
