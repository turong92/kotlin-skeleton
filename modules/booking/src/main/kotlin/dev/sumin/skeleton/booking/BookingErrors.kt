package dev.sumin.skeleton.booking

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

enum class BookingErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    SEAT_TAKEN("BOOKING.SEAT_TAKEN", HttpStatus.CONFLICT, "다른 분이 먼저 잡은 자리가 있다"),
    LIMIT_REACHED("BOOKING.LIMIT_REACHED", HttpStatus.CONFLICT, "한 번에 잡을 수 있는 자리 수를 넘었다"),
    NOT_OPEN("BOOKING.NOT_OPEN", HttpStatus.CONFLICT, "지금은 자리를 잡을 수 있는 때가 아니다"),
    HOLD_EXPIRED("BOOKING.HOLD_EXPIRED", HttpStatus.CONFLICT, "보류 시간이 지나 자리가 풀렸다"),
    HOLD_LOCKED("BOOKING.HOLD_LOCKED", HttpStatus.CONFLICT, "결제를 시작해 자리를 바꿀 수 없다 — 모두 놓고 다시 고른다"),
    HOLD_EMPTY("BOOKING.HOLD_EMPTY", HttpStatus.CONFLICT, "잡은 자리가 없다"),
    PAYMENT_REQUIRED("BOOKING.PAYMENT_REQUIRED", HttpStatus.CONFLICT, "유료 자리는 결제로 확정한다"),
    NOT_CANCELABLE("BOOKING.NOT_CANCELABLE", HttpStatus.CONFLICT, "취소할 수 없는 예약이다"),
    SEAT_EXISTS("BOOKING.SEAT_EXISTS", HttpStatus.CONFLICT, "같은 구역 · 열 · 번호의 자리가 이미 있다"),
    SEAT_IN_USE("BOOKING.SEAT_IN_USE", HttpStatus.CONFLICT, "잡히거나 팔린 자리는 막을 수 없다"),
    POOL_NOT_FOUND("BOOKING.POOL_NOT_FOUND", HttpStatus.NOT_FOUND, "예약 풀이 없다"),
    HOLD_NOT_FOUND("BOOKING.HOLD_NOT_FOUND", HttpStatus.NOT_FOUND, "보류가 없다"),
    SEAT_NOT_FOUND("BOOKING.SEAT_NOT_FOUND", HttpStatus.NOT_FOUND, "자리가 없다"),
}

/**
 * 하나라도 못 잡았다 — 이 트랜잭션은 통째로 되돌려졌다. [seatIds] 는 못 잡은 자리 전부.
 * [reclaimableHoldIds] 는 그중 **보류 시간이 지났는데 아직 회수 전인** 자리의 보류 — 서비스가 그 보류를 회수하고 한 번 다시 시도한다(응답에는 나가지 않는다).
 */
class SeatTakenException(val seatIds: List<String>, val reclaimableHoldIds: Set<String> = emptySet()) :
    ApplicationException("seats taken: $seatIds", BookingErrorCode.SEAT_TAKEN, data = mapOf("seatIds" to seatIds))

class LimitReachedException : ApplicationException("booking limit reached", BookingErrorCode.LIMIT_REACHED)

class NotOpenException : ApplicationException("booking pool is not open", BookingErrorCode.NOT_OPEN)

/** 내 보류가 시간이 지났다. 서비스가 [holdId] 를 회수한 뒤 그대로 응답한다 */
class HoldExpiredException(val holdId: String) : ApplicationException("hold expired: $holdId", BookingErrorCode.HOLD_EXPIRED)

class HoldLockedException : ApplicationException("hold is locked for checkout", BookingErrorCode.HOLD_LOCKED)

class HoldEmptyException : ApplicationException("hold has no seats", BookingErrorCode.HOLD_EMPTY)

class PaymentRequiredException : ApplicationException("priced seats are confirmed by payment", BookingErrorCode.PAYMENT_REQUIRED)

class NotCancelableException : ApplicationException("booking cannot be canceled", BookingErrorCode.NOT_CANCELABLE)

class SeatExistsException : ApplicationException("seat already exists", BookingErrorCode.SEAT_EXISTS)

class SeatInUseException(seatIds: List<String>) :
    ApplicationException("seats in use: $seatIds", BookingErrorCode.SEAT_IN_USE, data = mapOf("seatIds" to seatIds))

class PoolNotFoundException : ApplicationException("booking pool not found", BookingErrorCode.POOL_NOT_FOUND)

/** 없는 보류도 남의 보류도 같은 404 — 남의 보류가 있는지 알리지 않는다 */
class HoldNotFoundException : ApplicationException("booking hold not found", BookingErrorCode.HOLD_NOT_FOUND)

class SeatNotFoundException : ApplicationException("booking seat not found", BookingErrorCode.SEAT_NOT_FOUND)
