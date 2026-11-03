package dev.sumin.skeleton.booking.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.booking.BookingHold
import dev.sumin.skeleton.booking.BookingPool
import dev.sumin.skeleton.booking.BookingSeat
import dev.sumin.skeleton.booking.SeatChange
import dev.sumin.skeleton.booking.SeatCounts
import dev.sumin.skeleton.booking.SeatSnapshot
import dev.sumin.skeleton.booking.SeatState
import dev.sumin.skeleton.booking.toChange
import java.time.Instant

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PoolResponse(
    val id: String,
    val name: String,
    val opensAt: Instant,
    val closesAt: Instant?,
    val currency: String?,
    val maxPerHold: Int,
    val perAccountLimit: Int?,
    val holdMinutes: Int,
    val waitingRoomId: String?,
    val status: String,
    val seats: SeatCountsResponse,
) {
    companion object {
        fun of(pool: BookingPool, counts: SeatCounts) = PoolResponse(
            pool.id, pool.name, pool.opensAt, pool.closesAt, pool.currency, pool.maxPerHold, pool.perAccountLimit,
            pool.holdMinutes, pool.waitingRoomId, pool.status.name, SeatCountsResponse(counts.total, counts.available),
        )
    }
}

data class SeatCountsResponse(val total: Int, val available: Int)

/** 배치도 한 칸 — [state] 는 `OPEN | TAKEN` 둘뿐이다. 보류 중인 자리는 `TAKEN` 에 [heldUntil] 이 붙는다 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SeatResponse(
    val id: String,
    val section: String,
    val row: String,
    val number: Int,
    val grade: String,
    val price: Long?,
    val state: String,
    val heldUntil: Instant?,
    val v: Long,
) {
    companion object {
        fun of(seat: BookingSeat): SeatResponse {
            val change = seat.toChange()
            return SeatResponse(seat.id, seat.section, seat.row, seat.number, seat.grade, seat.price, change.state.name, change.heldUntil, change.v)
        }
    }
}

data class SnapshotResponse(val serverTime: Instant, val seats: List<SeatResponse>) {
    companion object {
        fun of(snapshot: SeatSnapshot) = SnapshotResponse(snapshot.serverTime, snapshot.seats.map(SeatResponse::of))
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class HoldResponse(
    val holdId: String,
    val poolId: String,
    val status: String,
    val seats: List<SeatResponse>,
    val expiresAt: Instant,
    val amount: Long?,
) {
    companion object {
        fun of(hold: BookingHold) = HoldResponse(
            hold.id, hold.poolId, hold.status.name, hold.seats.map(SeatResponse::of), hold.expiresAt, hold.amount ?: hold.total,
        )
    }
}

data class HoldSeatsRequest(val seatIds: List<String>? = null)

/** 배치도 흐름의 `seats` 사건 한 칸 — 화면이 `v` 가 큰 쪽을 남긴다 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SeatEvent(val id: String, val state: SeatState, val heldUntil: Instant?, val v: Long) {
    companion object {
        fun of(change: SeatChange) = SeatEvent(change.seatId, change.state, change.heldUntil, change.v)
    }
}

data class SeatsEvent(val seats: List<SeatEvent>)

data class ReadyEvent(val poolId: String)
