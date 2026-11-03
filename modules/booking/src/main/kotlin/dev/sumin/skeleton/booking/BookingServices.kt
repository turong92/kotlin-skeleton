package dev.sumin.skeleton.booking

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.time.TimeProvider
import org.slf4j.LoggerFactory

/** 한 번에 다룰 수 있는 자리 수 상한(입력 크기 방어) — 풀의 `maxPerHold` 와 별개 */
private const val MAX_SEATS_PER_CALL = 500

/** 만료 회수 한 번에 훑는 보류 수 */
private const val EXPIRE_BATCH = 200

/** 앱이 쓰는 서버 API — 풀 · 자리 목록. 자리 상태가 바뀌면 커밋 뒤에 사건을 중계한다 */
class BookingPools(
    private val store: BookingStore,
    private val relay: BookingSeatRelay,
    private val time: TimeProvider,
) {
    fun create(pool: NewBookingPool): BookingPool = store.createPool(pool, time.now())

    fun find(id: String): BookingPool? = store.findPool(id)

    fun get(id: String): BookingPool = store.findPool(id) ?: throw PoolNotFoundException()

    fun counts(poolId: String): SeatCounts = store.counts(get(poolId).id)

    fun addSeats(poolId: String, seats: List<NewSeat>): List<BookingSeat> = store.addPoolSeats(get(poolId).id, seats, time.now())

    /** 막기 — 잡히거나 팔린 자리는 [SeatInUseException] */
    fun block(poolId: String, seatIds: List<String>) {
        relay.safely(poolId, store.blockSeats(get(poolId).id, seatIds.distinct()))
    }

    fun unblock(poolId: String, seatIds: List<String>) {
        relay.safely(poolId, store.unblockSeats(get(poolId).id, seatIds.distinct()))
    }

    /** 자리 목록과 지금 상태를 한 번에 — 캐시하지 않는다(캐시된 옛 상태와 연결 전 사건 사이에 빈틈이 생긴다) */
    fun snapshot(poolId: String): SeatSnapshot {
        val pool = get(poolId)
        // 시각은 읽기 전에 정한다 — 이 시각 뒤의 변화는 사건으로 온다
        val at = time.now()
        return SeatSnapshot(at, store.seats(pool.id))
    }
}

data class SeatSnapshot(val serverTime: java.time.Instant, val seats: List<BookingSeat>)

/**
 * 앱이 쓰는 서버 API — 자리 잡기 · 놓기 · 모두 놓기 · 확정 · 취소 · 만료 회수.
 * 저장소 쓰기가 끝나(=커밋된) 뒤에 자리 사건을 한 번 중계한다. 롤백된 쓰기는 사건이 없다.
 */
class BookingHolds(
    private val store: BookingStore,
    private val relay: BookingSeatRelay,
    private val admission: BookingAdmission,
    private val time: TimeProvider,
) {
    /**
     * 내 보류에 자리를 더한다(없으면 보류를 만든다). 여럿이면 전부 아니면 전무.
     * 막은 자리가 **보류 시간이 지났는데 아직 회수 전**이면 그 보류를 그 자리에서 회수하고 한 번만 다시 한다 — 그래도 막히면 [SeatTakenException].
     */
    fun addSeats(poolId: String, accountId: String, seatIds: List<String>, entryToken: String? = null): BookingHold {
        val ids = seatIds.distinct()
        if (ids.isEmpty()) throw FieldValidationException("seatIds", "Size", "pick at least one seat")
        if (ids.size > MAX_SEATS_PER_CALL) throw FieldValidationException("seatIds", "Size", "too many seats")
        val pool = store.findPool(poolId) ?: throw PoolNotFoundException()
        if (ids.size > pool.maxPerHold) throw LimitReachedException()
        admission.check(pool, accountId, entryToken)
        return withExpiredCleanup {
            try {
                commit(poolId, store.addToHold(poolId, accountId, ids, time.now()))
            } catch (blocked: SeatTakenException) {
                if (blocked.reclaimableHoldIds.isEmpty()) throw blocked
                blocked.reclaimableHoldIds.forEach { expire(it) }
                commit(poolId, store.addToHold(poolId, accountId, ids, time.now()))
            }
        }
    }

    fun removeSeat(poolId: String, accountId: String, seatId: String): BookingHold =
        withExpiredCleanup { commit(poolId, store.removeFromHold(poolId, accountId, seatId, time.now())) }

    /** 모두 놓기(「나가기」 · 「자리 다시 고르기」). `HELD` 가 아닌 보류는 그대로 돌려준다 — 늦게 도착한 놓기가 확정된 예약을 건드리지 않는다 */
    fun release(accountId: String, holdId: String): BookingHold {
        val outcome = store.release(accountId, holdId, time.now())
        return commit(outcome.hold.poolId, outcome)
    }

    /** 무료 확정 */
    fun confirm(accountId: String, holdId: String): BookingHold =
        withExpiredCleanup {
            val outcome = store.confirmFree(accountId, holdId, time.now())
            commit(outcome.hold.poolId, outcome)
        }

    /** 무료 확정의 본인 취소. 유료는 결제 환불이 정본이다 */
    fun cancel(accountId: String, holdId: String): BookingHold {
        val outcome = store.cancel(accountId, holdId, time.now())
        return commit(outcome.hold.poolId, outcome)
    }

    fun mine(accountId: String): List<BookingHold> = store.holdsOf(accountId, time.now())

    /** 시간이 지난 보류를 회수한다(주기 작업 · 요청 구동). 여러 인스턴스가 동시에 돌려도 보류 줄 전이가 1줄일 때만 자리가 돌아와 한 번만 풀린다. 회수한 보류 수 */
    fun expireDue(): Int {
        var reclaimed = 0
        while (true) {
            val due = store.dueHolds(time.now(), EXPIRE_BATCH)
            if (due.isEmpty()) return reclaimed
            val before = reclaimed
            due.forEach { if (expire(it)) reclaimed++ }
            if (reclaimed == before) return reclaimed   // 읽은 것을 못 닫았다(다른 인스턴스가 먼저) - 같은 줄을 돌며 멈추지 않게
        }
    }

    /** 보류 하나를 회수하고 사건을 중계한다. 실제로 회수했으면 true */
    private fun expire(holdId: String): Boolean {
        val outcome = store.expire(holdId, time.now()) ?: return false
        commit(outcome.hold.poolId, outcome)
        return true
    }

    /** 내 보류가 시간이 지났다는 응답이면, 응답하기 전에 그 보류를 회수한다 — 다시 눌렀을 때 바로 새로 잡힌다 */
    private fun <T> withExpiredCleanup(action: () -> T): T =
        try {
            action()
        } catch (expired: HoldExpiredException) {
            expire(expired.holdId)
            throw expired
        }

    private fun commit(poolId: String, outcome: HoldOutcome): BookingHold {
        relay.safely(poolId, outcome.changes)
        return outcome.hold
    }
}

private fun BookingSeatRelay.safely(poolId: String, changes: List<SeatChange>) {
    if (changes.isEmpty()) return
    try {
        publish(poolId, changes)
    } catch (ex: Exception) {
        LoggerFactory.getLogger(BookingSeatRelay::class.java).warn("booking seat event not relayed: {}", ex.toString())
    }
}
