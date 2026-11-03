package dev.sumin.skeleton.booking

import java.time.Instant

enum class PoolStatus { ACTIVE, PAUSED, CLOSED }

enum class SeatStatus { AVAILABLE, HELD, SOLD, BLOCKED }

enum class HoldStatus { HELD, CONFIRMED, RELEASED, EXPIRED, CANCELED, UNFULFILLED }

/** 보류 시간 기본 — 안전 장치라 모듈 기본값이고 풀마다 덮는다 */
const val DEFAULT_HOLD_MINUTES = 10

/** 풀 = 회차 하나. 금액은 통화의 최소 단위 정수 */
data class BookingPool(
    val id: String,
    val name: String,
    val opensAt: Instant,
    val closesAt: Instant?,
    /** 한 번에 잡는 자리 상한 — 만들 때 필수(기본값 없음: 행사 성격을 모듈이 모른다) */
    val maxPerHold: Int,
    val perAccountLimit: Int?,
    val currency: String?,
    val holdMinutes: Int,
    val waitingRoomId: String?,
    val status: PoolStatus,
    val createdAt: Instant,
) {
    /** 지금 자리를 잡을 수 있는 때인가 */
    fun isOpen(now: Instant): Boolean = status == PoolStatus.ACTIVE && !now.isBefore(opensAt) && (closesAt == null || now.isBefore(closesAt))
}

data class NewBookingPool(
    val name: String,
    val opensAt: Instant,
    val closesAt: Instant? = null,
    val maxPerHold: Int,
    val perAccountLimit: Int? = null,
    val currency: String? = null,
    val holdMinutes: Int = DEFAULT_HOLD_MINUTES,
    val waitingRoomId: String? = null,
) {
    init {
        require(name.isNotBlank()) { "pool name must not be blank" }
        require(maxPerHold >= 1) { "maxPerHold must be at least 1" }
        require(holdMinutes >= 1) { "holdMinutes must be at least 1" }
        require(perAccountLimit == null || perAccountLimit >= 1) { "perAccountLimit must be at least 1" }
        require(closesAt == null || closesAt.isAfter(opensAt)) { "closesAt must be after opensAt" }
    }
}

/** 새 자리 한 칸 — 구역 · 열 · 번호가 풀 안에서 유일하다. [price] 가 없으면 무료 */
data class NewSeat(val section: String, val row: String, val number: Int, val grade: String, val price: Long? = null) {
    init {
        require(section.isNotBlank() && row.isNotBlank() && grade.isNotBlank()) { "seat section / row / grade must not be blank" }
        require(price == null || price >= 0) { "seat price must not be negative" }
    }
}

/** [version] 은 자리 상태가 바뀔 때마다 1 오른다 — 화면이 사건 순서 뒤바뀜을 거르는 데 쓴다 */
data class BookingSeat(
    val id: String,
    val poolId: String,
    val section: String,
    val row: String,
    val number: Int,
    val grade: String,
    val price: Long?,
    val status: SeatStatus,
    val holdId: String?,
    val heldUntil: Instant?,
    val version: Long,
)

/** 보류 하나 — 한 계정이 한 풀에서 잡고 있는 자리 묶음. [seats] 는 이 보류가 가졌던 자리 */
data class BookingHold(
    val id: String,
    val poolId: String,
    val accountId: String,
    val status: HoldStatus,
    val seatCount: Int,
    val amount: Long?,
    val expiresAt: Instant,
    val checkoutStartedAt: Instant?,
    val confirmedOrderId: String?,
    val seats: List<BookingSeat>,
    val createdAt: Instant,
) {
    /** 자리 가격의 합. 가격이 붙은 자리가 하나도 없으면(무료) null — 서버만 정한다 */
    val total: Long? get() = seats.mapNotNull { it.price }.takeIf { it.isNotEmpty() }?.sum()
}

/** 화면에 보이는 자리 상태는 둘뿐이다 — 잡힌 · 팔린 · 막힌 자리는 모두 [TAKEN], [TAKEN] 에 heldUntil 이 있으면 보류(그 시각에 열린다) */
enum class SeatState { OPEN, TAKEN }

/** 자리 하나의 변화(자리 사건). [v] 는 그 자리의 [BookingSeat.version] */
data class SeatChange(val seatId: String, val state: SeatState, val heldUntil: Instant?, val v: Long)

fun BookingSeat.toChange(): SeatChange = when (status) {
    SeatStatus.AVAILABLE -> SeatChange(id, SeatState.OPEN, null, version)
    SeatStatus.HELD -> SeatChange(id, SeatState.TAKEN, heldUntil, version)
    SeatStatus.SOLD, SeatStatus.BLOCKED -> SeatChange(id, SeatState.TAKEN, null, version)
}

/** 저장소 한 번의 쓰기 결과 — 그 트랜잭션이 바꾼 자리의 변화. 서비스가 커밋 뒤에 중계한다 */
data class HoldOutcome(val hold: BookingHold, val changes: List<SeatChange>)

data class SeatCounts(val total: Int, val available: Int)

/** 풀 현황(운영자). [unfulfilled] 는 돈은 받았는데 자리를 못 준 보류 수 */
data class PoolStats(val total: Int, val available: Int, val held: Int, val sold: Int, val blocked: Int, val unfulfilled: Int)

/**
 * 결제된 주문 하나를 보류에 맞춘 결과. [CONFIRMED] 면 그 주문이 보류를 확정했다(자리 `SOLD`).
 * [UNFULFILLED] 는 자리를 못 줘서(다시 못 잡았다 · 금액이 다르다) · [DUPLICATE] 는 같은 보류가 이미 다른 주문으로 확정돼서 — 둘 다 환불 작업이 하나 걸린다.
 */
enum class OrderOutcome { CONFIRMED, UNFULFILLED, DUPLICATE }

data class PaidOutcome(val outcome: OrderOutcome, val hold: BookingHold, val changes: List<SeatChange>)
