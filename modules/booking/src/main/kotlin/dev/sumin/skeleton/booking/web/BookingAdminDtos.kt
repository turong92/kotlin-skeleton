package dev.sumin.skeleton.booking.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.booking.BookingPool
import dev.sumin.skeleton.booking.PoolStats
import java.time.Instant

data class CreatePoolRequest(
    val name: String? = null,
    val opensAt: Instant? = null,
    val closesAt: Instant? = null,
    val maxPerHold: Int? = null,
    val perAccountLimit: Int? = null,
    val currency: String? = null,
    val holdMinutes: Int? = null,
    val waitingRoomId: String? = null,
)

/** 넣은 값만 바뀐다 - 판매 끝은 바꿀 수만 있고 지울 수는 없다 */
data class UpdatePoolRequest(val name: String? = null, val closesAt: Instant? = null, val status: String? = null)

data class SeatInput(val section: String? = null, val row: String? = null, val number: Int? = null, val grade: String? = null, val price: Long? = null)

data class AddSeatsRequest(val seats: List<SeatInput>? = null)

data class SeatIdsRequest(val seatIds: List<String>? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AdminPoolResponse(
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
    val createdAt: Instant,
) {
    companion object {
        fun of(pool: BookingPool) = AdminPoolResponse(
            pool.id, pool.name, pool.opensAt, pool.closesAt, pool.currency, pool.maxPerHold, pool.perAccountLimit,
            pool.holdMinutes, pool.waitingRoomId, pool.status.name, pool.createdAt,
        )
    }
}

data class AddedSeatsResponse(val added: Int)

data class ChangedSeatsResponse(val changed: Int)

/** 현황 - [unfulfilled] 는 돈은 받았는데 자리를 못 준 보류 수(환불 작업이 걸려 있다) */
data class PoolStatsResponse(val total: Int, val available: Int, val held: Int, val sold: Int, val blocked: Int, val unfulfilled: Int) {
    companion object {
        fun of(stats: PoolStats) = PoolStatsResponse(stats.total, stats.available, stats.held, stats.sold, stats.blocked, stats.unfulfilled)
    }
}
