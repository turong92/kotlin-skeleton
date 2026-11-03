package dev.sumin.skeleton.waitingroom.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.waitingroom.QueueStatus
import dev.sumin.skeleton.waitingroom.WaitingRoom
import dev.sumin.skeleton.waitingroom.WaitingRoomStats
import java.time.Instant

/** 묻기 · 순번표 받기의 답. `ticket` 은 새 순번표가 만들어졌을 때만 있다 - 있으면 저장한 것을 이것으로 바꾼다. `entryToken` 은 ADMITTED 일 때만 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueueStatusResponse(
    val state: String,
    val position: Long?,
    val ahead: Long?,
    val estimatedWaitSeconds: Long?,
    val opensAt: Instant,
    val pollAfterSeconds: Int,
    val entryToken: String?,
    val ticket: String?,
) {
    companion object {
        fun of(status: QueueStatus) = QueueStatusResponse(
            status.state.name, status.position, status.ahead, status.estimatedWaitSeconds, status.opensAt, status.pollAfterSeconds,
            status.entryToken, status.ticket,
        )
    }
}

data class CreateWaitingRoomRequest(
    val scopeKey: String? = null,
    val opensAt: Instant? = null,
    val perMinute: Int? = null,
    val prequeueMinutes: Int? = null,
    val entryMinutes: Int? = null,
)

data class SetRateRequest(val perMinute: Int? = null)

data class WaitingRoomResponse(
    val id: String,
    val scopeKey: String,
    val opensAt: Instant,
    val prequeueStartsAt: Instant,
    val entryMinutes: Long,
    val status: String,
) {
    companion object {
        fun of(room: WaitingRoom) = WaitingRoomResponse(
            room.id, room.scopeKey, room.opensAt, room.prequeueStartsAt, room.entryTtl.toMinutes(), room.status.name,
        )
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WaitingRoomStatsResponse(
    val prequeue: Long,
    val issued: Long,
    val serving: Long,
    val perMinute: Int,
    val estimatedDrainSeconds: Long?,
) {
    companion object {
        fun of(stats: WaitingRoomStats) = WaitingRoomStatsResponse(stats.prequeue, stats.issued, stats.serving, stats.perMinute, stats.estimatedDrainSeconds)
    }
}
