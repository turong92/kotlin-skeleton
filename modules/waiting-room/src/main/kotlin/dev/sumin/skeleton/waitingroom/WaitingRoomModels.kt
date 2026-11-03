package dev.sumin.skeleton.waitingroom

import java.time.Instant

enum class RoomStatus { ACTIVE, CLOSED }

/**
 * 대기실 하나. [queueTail] 은 지금까지 매긴 번호의 끝(다음 사람은 +1). [shuffledAt] 은 오픈 순간의 섞기가 끝난 시각 - 한 번만 채워진다.
 * 입장 시간은 번호가 서빙에 닿은 시각부터 [entryTtl].
 */
data class WaitingRoom(
    val id: String,
    val scopeKey: String,
    val opensAt: Instant,
    val prequeueStartsAt: Instant,
    val entryTtl: java.time.Duration,
    val status: RoomStatus,
    val shuffledAt: Instant?,
    val queueTail: Long,
    val createdAt: Instant,
)

/** 속도 구간 - [fromAt] 부터 분당 [perMinute] 명을 들여보낸다(0 = 멈춤). 오픈 시각 앞의 구간은 오픈 때의 속도가 된다 */
data class RateSegment(val fromAt: Instant, val perMinute: Int)

/** 순번표 한 줄. [queueNo] 는 오픈 전 줄이 섞이기 전에는 null */
data class WaitingTicket(
    val id: String,
    val roomId: String,
    val accountId: String,
    val queueNo: Long?,
    val createdAt: Instant,
)

/** 대기실을 만들 때 - [perMinute] 은 필수(서버 크기 · 행사 성격을 모듈이 모른다). 나머지는 안전 장치라 기본값이 있다 */
data class NewWaitingRoom(
    val scopeKey: String,
    val opensAt: Instant,
    val perMinute: Int,
    val prequeueMinutes: Int = DEFAULT_PREQUEUE_MINUTES,
    val entryMinutes: Int = DEFAULT_ENTRY_MINUTES,
) {
    companion object {
        const val DEFAULT_PREQUEUE_MINUTES = 30
        const val DEFAULT_ENTRY_MINUTES = 10
    }
}

enum class QueueState { PREQUEUE, WAITING, ADMITTED, EXPIRED, CLOSED }

/**
 * 묻기 · 순번표 받기의 답. [ticket] 은 순번표 토큰이 새로 만들어졌을 때만 있다(받을 때, 오픈 뒤 번호가 처음 알려졌을 때) - 화면은 이 값으로 저장한 토큰을 바꾼다.
 * [entryToken] 은 [QueueState.ADMITTED] 일 때만.
 */
data class QueueStatus(
    val state: QueueState,
    val position: Long?,
    val ahead: Long?,
    val estimatedWaitSeconds: Long?,
    val opensAt: Instant,
    val pollAfterSeconds: Int,
    val entryToken: String? = null,
    val ticket: String? = null,
)

data class WaitingRoomStats(
    val prequeue: Long,
    val issued: Long,
    val serving: Long,
    val perMinute: Int,
    val estimatedDrainSeconds: Long?,
)
