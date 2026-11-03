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

    /** 풀에 자리를 더한다. 같은 구역 · 열 · 번호가 있으면 [SeatExistsException] (아무것도 안 더해진다) */
    fun addPoolSeats(poolId: String, seats: List<NewSeat>, now: Instant): List<BookingSeat>

    /** `AVAILABLE → BLOCKED`. 잡히거나 팔린 자리가 하나라도 있으면 [SeatInUseException]. 이미 막힌 자리는 건너뛴다 */
    fun blockSeats(poolId: String, seatIds: List<String>): List<SeatChange>

    /** `BLOCKED → AVAILABLE`. 막히지 않은 자리는 건드리지 않는다 */
    fun unblockSeats(poolId: String, seatIds: List<String>): List<SeatChange>

    /** 풀의 모든 자리와 지금 상태 (구역 · 열 · 번호 순) */
    fun seats(poolId: String): List<BookingSeat>

    fun counts(poolId: String): SeatCounts

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

    /** 만료된 보류 하나를 회수한다. 이미 회수 · 놓기 · 확정된 보류거나 아직 안 지났으면 null — 여러 번 불려도 자리는 한 번만 풀린다 */
    fun expire(holdId: String, now: Instant): HoldOutcome?

    /** 만료 시각이 지났는데 아직 `HELD` 인 보류 id (오래된 것부터) */
    fun dueHolds(now: Instant, limit: Int): List<String>

    /** 내 열린(만료 전) · 확정 보류 */
    fun holdsOf(accountId: String, now: Instant): List<BookingHold>

    fun findHold(accountId: String, holdId: String): BookingHold?
}
