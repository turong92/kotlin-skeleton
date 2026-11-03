package dev.sumin.skeleton.booking

import java.time.Instant

/**
 * 예약 저장 포트 — `booking-jdbc` 가 DB 로 채운다. **쓰기 메서드 하나가 트랜잭션 하나**이고 돌려주는 변화는 그 트랜잭션이 커밋된 뒤의 것이다.
 * 실패는 예외로 던지며 그 트랜잭션은 통째로 되돌려진다([BookingErrorCode]). 이중 판매 0 의 정본은 이 포트의 구현이다 — 자리 줄 조건부 갱신(`status = 'AVAILABLE'`일 때만).
 *
 * 보류 상태를 바꾸는 모든 쓰기는 그 계정의 사용량 줄을 먼저 잠근다(계정 안에서 직렬) → 자리는 늘 id 오름차순으로 한 줄씩. 그래서 {X, Y} 와 {Y, X} 를 동시에 잡아도 교착이 없다.
 */
interface BookingStore {
    fun createPool(pool: NewBookingPool, now: Instant): BookingPool

    fun findPool(id: String): BookingPool?

    /** 운영자용 목록 - 새것부터, 200개까지 */
    fun listPools(): List<BookingPool>

    /** 이름 · 판매 끝 · 상태를 바꾼다(`null` 은 그대로). 없는 풀은 [PoolNotFoundException] */
    fun updatePool(poolId: String, name: String?, closesAt: Instant?, status: PoolStatus?): BookingPool

    /** 풀에 자리를 더한다. 같은 구역 · 열 · 번호가 있으면 [SeatExistsException] (아무것도 안 더해진다) */
    fun addPoolSeats(poolId: String, seats: List<NewSeat>, now: Instant): List<BookingSeat>

    /** `AVAILABLE → BLOCKED`. 잡히거나 팔린 자리가 하나라도 있으면 [SeatInUseException]. 이미 막힌 자리는 건너뛴다 */
    fun blockSeats(poolId: String, seatIds: List<String>): List<SeatChange>

    /** `BLOCKED → AVAILABLE`. 막히지 않은 자리는 건드리지 않는다 */
    fun unblockSeats(poolId: String, seatIds: List<String>): List<SeatChange>

    /** 풀의 모든 자리와 지금 상태 (구역 · 열 · 번호 순) */
    fun seats(poolId: String): List<BookingSeat>

    fun counts(poolId: String): SeatCounts

    fun stats(poolId: String): PoolStats

    /** 내 열린 보류에 자리를 더한다(없으면 보류를 만든다). 이미 내 보류에 있는 자리는 건너뛴다 */
    fun addToHold(poolId: String, accountId: String, seatIds: List<String>, now: Instant): HoldOutcome

    /** 다시 눌러 풀기. 이미 풀린 자리면 아무것도 안 한다 */
    fun removeFromHold(poolId: String, accountId: String, seatId: String, now: Instant): HoldOutcome

    /** 모두 놓기 — `HELD` 일 때만 자리가 돌아온다. 다른 상태면 보류를 그대로 돌려준다 */
    fun release(accountId: String, holdId: String, now: Instant): HoldOutcome

    /** 무료 확정 — 자리 모두 가격이 없고 만료 전일 때만 */
    fun confirmFree(accountId: String, holdId: String, now: Instant): HoldOutcome

    /** 무료 확정의 본인 취소(판매 끝 전까지) */
    fun cancel(accountId: String, holdId: String, now: Instant): HoldOutcome

    /**
     * **결제 시작** - 금액(= 자리 가격의 합, 서버만 정한다)을 보류에 박고 **자리를 고정**한다: 그 뒤 더하기 · 빼기 · 모두 놓기는 [HoldLockedException], 보류 시간이 끝나면 그대로 반환된다(늘지 않는다).
     * 멱등 - 이미 시작했으면 박힌 그대로 돌려준다. 무료 · 이미 확정된 보류는 [NotPayableException], 시간이 지났으면 [HoldExpiredException]. 쓰기라 잠금 순서(사용량 줄 → 보류 줄)를 지킨다.
     */
    fun startCheckout(accountId: String, holdId: String, now: Instant): BookingHold

    /**
     * 주문(`itemFor`)을 위해 결제를 시작한 보류의 박힌 금액을 본다 - **아무것도 바꾸지 않는다**(결제 모듈의 견적 `/orders/quote` 도 같은 길을 타서 부수 효과가 있으면 미리보기만으로 묶인다).
     * 결제를 시작하지 않은 보류는 [CheckoutRequiredException] - 그래서 자리가 고정되지 않은 채 주문이 만들어지지 않는다.
     */
    fun priceHold(accountId: String, holdId: String, now: Instant): BookingHold

    /**
     * 주문 [orderId] 가 결제됐다(`onPaid`). 주문 하나에 한 번만 효과가 있고(다시 불려도 같은 결과) 결과는 [OrderOutcome] 이다.
     * 보류가 `HELD` 면(시간이 지났어도 아직 회수 전이면) 확정, 이미 회수 · 놓기 된 보류면 가졌던 자리를 **전부 아니면 전무**로 다시 잡아 확정하고 못 잡으면 `UNFULFILLED`,
     * 이미 다른 주문으로 확정된 보류면 이 주문은 [OrderOutcome.DUPLICATE]. [quotedAmount] · [currency] 가 지금 보류의 자리 가격 합 · 풀의 통화와 다르면(견적 뒤 자리를 바꿨다) 보류는 그대로 두고 환불한다.
     * 환불이 필요한 결과는 같은 트랜잭션에서 환불 작업을 넣는다([BookingRefundQueue]) - 돈만 받고 방치되지 않게.
     */
    fun settlePaid(orderId: String, accountId: String, holdId: String, quotedAmount: Long, currency: String, now: Instant): PaidOutcome

    /** 확정에 쓰인 주문이 전액 환불됐다 - 보류 `CANCELED` · 자리 반환. 그 주문으로 확정된 보류가 아니면 null(여러 번 불려도 한 번만) */
    fun cancelRefunded(orderId: String, now: Instant): HoldOutcome?

    /** 만료된 보류 하나를 회수한다. 이미 회수 · 놓기 · 확정된 보류거나 아직 안 지났으면 null — 여러 번 불려도 자리는 한 번만 풀린다 */
    fun expire(holdId: String, now: Instant): HoldOutcome?

    /** 만료 시각이 지났는데 아직 `HELD` 인 보류 id (오래된 것부터) */
    fun dueHolds(now: Instant, limit: Int): List<String>

    /** 내 열린(만료 전) · 확정 보류 */
    fun holdsOf(accountId: String, now: Instant): List<BookingHold>

    fun findHold(accountId: String, holdId: String): BookingHold?
}
