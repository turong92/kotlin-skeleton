package dev.sumin.skeleton.app.sample.booking

import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.NewBookingPool
import dev.sumin.skeleton.booking.NewSeat
import dev.sumin.skeleton.waitingroom.NewWaitingRoom
import dev.sumin.skeleton.waitingroom.WaitingRooms
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 씨앗 - 예약 풀 둘과 각자의 대기실. **이름(풀) · scope key(대기실)로 찾아 없는 것만** 만든다 - 다시 떠도 늘지 않고, 운영 화면에서 고친 것을 덮지 않는다.
 *
 * - 「워크숍 20석」: 무료, 한 등급(일반) 20석(A구역 1~2열 × 10), 한 번에 2석. 대기실 `sample-workshop`.
 * - 「소극장 공연 120석」: 유료 KRW, R석 40(A구역 1~4열 × 10, 50,000원) · S석 80(B구역 1~8열 × 10, 30,000원), 한 번에 4석. 대기실 `sample-theater`.
 * - 대기실 둘 다 분당 30명, 이미 열려 있다(오픈 1분 전부터) - 로그인해서 순번표를 받으면 곧 입장한다.
 *
 * 「오픈 전 → 오픈」 여정은 이 씨앗이 아니라 운영자 경로(`/api/v1/admin/waiting-rooms` · `/api/v1/admin/booking/pools`, 로컬은 켜져 있다)로
 * 오픈 시각이 몇 초 뒤인 새 대기실 · 풀을 만들어 본다 - 시계를 건드리지 않는다(docs/sample.md 「예약」).
 */
@Component
@Profile("local")
class BookingSeedRunner(private val pools: BookingPools, private val rooms: WaitingRooms) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = BookingSeed(pools, rooms).run()
}

class BookingSeed(
    private val pools: BookingPools,
    private val rooms: WaitingRooms,
    private val now: () -> Instant = { Instant.now() },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run() {
        val opened = now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.MINUTES)
        val existing = pools.list().associateBy { it.name }

        if (WORKSHOP !in existing) {
            val room = room(WORKSHOP_ROOM, opened)
            val pool = pools.create(NewBookingPool(WORKSHOP, opened, maxPerHold = 2, waitingRoomId = room))
            pools.addSeats(pool.id, grid("A", 1..2, "일반", price = null))
            log.info("seeded booking pool '{}' id={} waitingRoom={}", WORKSHOP, pool.id, room)
        }
        if (THEATER !in existing) {
            val room = room(THEATER_ROOM, opened)
            val pool = pools.create(NewBookingPool(THEATER, opened, maxPerHold = 4, currency = "KRW", waitingRoomId = room))
            pools.addSeats(pool.id, grid("A", 1..4, "R", price = 50_000) + grid("B", 1..8, "S", price = 30_000))
            log.info("seeded booking pool '{}' id={} waitingRoom={}", THEATER, pool.id, room)
        }
    }

    /** 대기실을 scope key 로 찾고 없으면 분당 30명으로 만든다 - 대기실 id */
    private fun room(scopeKey: String, opensAt: Instant): String =
        (rooms.findByScope(scopeKey) ?: rooms.create(NewWaitingRoom(scopeKey, opensAt, perMinute = PER_MINUTE))).id

    private fun grid(section: String, rows: IntRange, grade: String, price: Long?): List<NewSeat> =
        rows.flatMap { row -> (1..SEATS_PER_ROW).map { number -> NewSeat(section, row.toString(), number, grade, price) } }

    companion object {
        const val WORKSHOP = "워크숍 20석"
        const val THEATER = "소극장 공연 120석"
        const val WORKSHOP_ROOM = "sample-workshop"
        const val THEATER_ROOM = "sample-theater"
        const val PER_MINUTE = 30
        private const val SEATS_PER_ROW = 10
    }
}
