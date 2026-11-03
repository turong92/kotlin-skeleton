package dev.sumin.skeleton.booking.jdbc

import dev.sumin.skeleton.booking.BookingHold
import dev.sumin.skeleton.booking.BookingPool
import dev.sumin.skeleton.booking.BookingSeat
import dev.sumin.skeleton.booking.BookingStore
import dev.sumin.skeleton.booking.HoldEmptyException
import dev.sumin.skeleton.booking.HoldExpiredException
import dev.sumin.skeleton.booking.HoldLockedException
import dev.sumin.skeleton.booking.HoldNotFoundException
import dev.sumin.skeleton.booking.HoldOutcome
import dev.sumin.skeleton.booking.HoldStatus
import dev.sumin.skeleton.booking.LimitReachedException
import dev.sumin.skeleton.booking.NewBookingPool
import dev.sumin.skeleton.booking.NewSeat
import dev.sumin.skeleton.booking.NotCancelableException
import dev.sumin.skeleton.booking.NotOpenException
import dev.sumin.skeleton.booking.PaymentRequiredException
import dev.sumin.skeleton.booking.PoolNotFoundException
import dev.sumin.skeleton.booking.PoolStatus
import dev.sumin.skeleton.booking.SeatChange
import dev.sumin.skeleton.booking.SeatCounts
import dev.sumin.skeleton.booking.SeatExistsException
import dev.sumin.skeleton.booking.SeatInUseException
import dev.sumin.skeleton.booking.SeatNotFoundException
import dev.sumin.skeleton.booking.SeatStatus
import dev.sumin.skeleton.booking.SeatTakenException
import dev.sumin.skeleton.booking.toChange
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * 예약 저장소 — 두 방언이 같은 SQL 이다. **이중 판매 0 의 정본은 자리 줄 조건부 갱신**(`update booking_seats … where id = ? and status = 'AVAILABLE'`, 바뀐 줄이 1일 때만 내 것)이고
 * 여러 자리는 한 트랜잭션에서 전부 아니면 전무(하나라도 못 잡으면 예외로 통째 롤백)다.
 *
 * 규칙 (모든 쓰기가 같다):
 * - **트랜잭션의 첫 문장은 그 계정의 사용량 줄 잠금**이다([inAccountTx] - 줄을 만드는 일만 트랜잭션 밖에서). 한 계정의 보류 쓰기가 여기서 직렬이 되어, 열린 보류를 찾거나 만드는 데 유니크 충돌 · 빈 줄 틈 잠금(MySQL)이 끼지 않는다.
 *   MySQL 반복 읽기의 스냅샷은 첫 일반 읽기에서 만들어지므로, **잠그기 전에 일반 읽기를 하지 않는다** — 누가 보류인지 알아내는 읽기는 트랜잭션 밖(자동 커밋)에서 한다.
 * - 잠금 순서: 사용량 줄 → 보류 줄(전이는 조건부 갱신) → 자리 줄(**id 오름차순, 한 문장에 한 줄**). 그래서 {X, Y} 와 {Y, X} 를 동시에 잡아도 교착이 없다.
 *   자리를 못 잡았을 때의 분류 읽기(`for update`)도 그 자리 차례에 하므로 순서가 깨지지 않는다.
 * - 보류 줄 전이(`HELD → 닫힘`)가 1줄일 때만 자리가 돌아온다 — 회수 · 놓기 · 확정이 겹쳐도 한 번만 풀린다.
 * - 자리가 바뀌면 `version = version + 1`. 변화는 그 트랜잭션이 잠근 줄을 읽어 만든다(커밋 뒤 중계는 서비스가 한다).
 */
class JdbcBookingStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
    private val ids: SortableIds = SortableIds.system,
) : BookingStore {
    private val insertUsage = dialect.insertIgnore("booking_usage", listOf("pool_id", "account_id", "seats"), listOf("pool_id", "account_id"))

    // ---- 풀 · 자리 -------------------------------------------------------------------------------------------------

    override fun createPool(pool: NewBookingPool, now: Instant): BookingPool {
        val id = ids.newId("bkp_")
        jdbc.update(
            "insert into booking_pools (id, name, opens_at, closes_at, max_per_hold, per_account_limit, currency, hold_minutes, waiting_room_id, status, created_at) " +
                "values (:id, :name, :opens, :closes, :max, :limit, :currency, :minutes, :room, :status, :now)",
            mapOf(
                "id" to id, "name" to pool.name, "opens" to ts(pool.opensAt), "closes" to ts(pool.closesAt), "max" to pool.maxPerHold,
                "limit" to pool.perAccountLimit, "currency" to pool.currency, "minutes" to pool.holdMinutes, "room" to pool.waitingRoomId,
                "status" to PoolStatus.ACTIVE.name, "now" to ts(now),
            ),
        )
        return checkNotNull(findPool(id))
    }

    override fun findPool(id: String): BookingPool? =
        jdbc.query("select * from booking_pools where id = :id", mapOf("id" to id)) { rs, _ -> rs.toPool() }.firstOrNull()

    override fun addPoolSeats(poolId: String, seats: List<NewSeat>, now: Instant): List<BookingSeat> {
        findPool(poolId) ?: throw PoolNotFoundException()
        val created = seats.map { BookingSeat(ids.newId("bks_"), poolId, it.section, it.row, it.number, it.grade, it.price, SeatStatus.AVAILABLE, null, null, 0) }
        try {
            tx.executeWithoutResult {
                created.forEach { seat ->
                    jdbc.update(
                        "insert into booking_seats (id, pool_id, section, row_label, number, grade, price_amount, status, version, created_at) " +
                            "values (:id, :pool, :section, :row, :number, :grade, :price, :status, 0, :now)",
                        mapOf(
                            "id" to seat.id, "pool" to poolId, "section" to seat.section, "row" to seat.row, "number" to seat.number,
                            "grade" to seat.grade, "price" to seat.price, "status" to SeatStatus.AVAILABLE.name, "now" to ts(now),
                        ),
                    )
                }
            }
        } catch (_: DuplicateKeyException) {
            throw SeatExistsException()
        }
        return created
    }

    override fun blockSeats(poolId: String, seatIds: List<String>): List<SeatChange> {
        val sorted = seatIds.distinct().sorted()
        return tx.execute {
            val changed = mutableListOf<String>()
            val inUse = mutableListOf<String>()
            for (id in sorted) {
                if (moveSeat(id, poolId, SeatStatus.AVAILABLE, SeatStatus.BLOCKED)) {
                    changed += id
                    continue
                }
                when (lockedStatus(id, poolId) ?: throw SeatNotFoundException()) {
                    SeatStatus.BLOCKED -> Unit
                    else -> inUse += id
                }
            }
            if (inUse.isNotEmpty()) throw SeatInUseException(inUse)
            changesOf(changed)
        }!!
    }

    override fun unblockSeats(poolId: String, seatIds: List<String>): List<SeatChange> {
        val sorted = seatIds.distinct().sorted()
        return tx.execute { changesOf(sorted.filter { moveSeat(it, poolId, SeatStatus.BLOCKED, SeatStatus.AVAILABLE) }) }!!
    }

    override fun seats(poolId: String): List<BookingSeat> =
        jdbc.query("select * from booking_seats where pool_id = :p order by section, row_label, number", mapOf("p" to poolId)) { rs, _ -> rs.toSeat() }

    override fun counts(poolId: String): SeatCounts =
        jdbc.queryForObject(
            "select count(*) as total, coalesce(sum(case when status = 'AVAILABLE' then 1 else 0 end), 0) as available from booking_seats where pool_id = :p",
            mapOf("p" to poolId),
        ) { rs, _ -> SeatCounts(rs.getInt("total"), rs.getInt("available")) }!!

    // ---- 보류 ------------------------------------------------------------------------------------------------------

    override fun addToHold(poolId: String, accountId: String, seatIds: List<String>, now: Instant): HoldOutcome {
        val pool = findPool(poolId) ?: throw PoolNotFoundException()
        if (!pool.isOpen(now)) throw NotOpenException()
        val wanted = seatIds.distinct().sorted()
        return inAccountTx(poolId, accountId) {
            val inPool = jdbc.queryForObject("select count(*) from booking_seats where pool_id = :p and id in (:ids)", mapOf("p" to poolId, "ids" to wanted), Int::class.java)
            if (inPool != wanted.size) throw SeatNotFoundException()
            val existing = openHold(poolId, accountId)
            if (existing != null) {
                if (!existing.expiresAt.isAfter(now)) throw HoldExpiredException(existing.id)
                if (existing.checkoutStartedAt != null) throw HoldLockedException()
            }
            val have = existing?.let { heldSeatIds(it.id) }.orEmpty()
            val fresh = wanted.filter { it !in have }
            if (fresh.isEmpty()) return@inAccountTx HoldOutcome(loadHold(checkNotNull(existing).id), emptyList())
            if ((existing?.seatCount ?: 0) + fresh.size > pool.maxPerHold) throw LimitReachedException()
            addUsage(poolId, accountId, fresh.size, pool.perAccountLimit)
            val hold = existing ?: insertHold(poolId, accountId, now.plusSeconds(pool.holdMinutes * 60L), now)

            val taken = mutableListOf<String>()
            val reclaimable = mutableSetOf<String>()
            for (id in fresh) {
                if (holdSeat(id, poolId, hold)) continue
                // 못 잡았다 - 지금 상태를 잠금 읽기로 본다. 그사이 풀렸다면(남이 놓았다 · 회수했다) 이미 잠근 그 줄을 바로 잡는다
                val blocker = lockedSeat(id)
                if (blocker != null && blocker.status == SeatStatus.AVAILABLE && holdSeat(id, poolId, hold)) continue
                taken += id
                // 보류 시간이 지났는데 아직 회수 전인 자리면 그 보류를 서비스가 회수하고 한 번 다시 한다
                val until = blocker?.heldUntil
                if (blocker != null && blocker.status == SeatStatus.HELD && until != null && !until.isAfter(now)) blocker.holdId?.let { reclaimable += it }
            }
            if (taken.isNotEmpty()) throw SeatTakenException(taken, reclaimable)

            fresh.forEach { seat -> jdbc.update("insert into booking_hold_seats (hold_id, seat_id) values (:h, :s)", mapOf("h" to hold.id, "s" to seat)) }
            jdbc.update("update booking_holds set seat_count = seat_count + :n where id = :id", mapOf("n" to fresh.size, "id" to hold.id))
            HoldOutcome(loadHold(hold.id), changesOf(fresh))
        }
    }

    override fun removeFromHold(poolId: String, accountId: String, seatId: String, now: Instant): HoldOutcome {
        findPool(poolId) ?: throw PoolNotFoundException()
        return inAccountTx(poolId, accountId) {
            val hold = openHold(poolId, accountId) ?: throw HoldNotFoundException()
            if (!hold.expiresAt.isAfter(now)) throw HoldExpiredException(hold.id)
            if (hold.checkoutStartedAt != null) throw HoldLockedException()
            if (seatId !in heldSeatIds(hold.id)) return@inAccountTx HoldOutcome(loadHold(hold.id), emptyList())
            // 자리 줄이 내 보류의 HELD 일 때만 풀린다. 사용량 · 보류의 자리 수는 그 한 줄과 같이 줄어든다
            check(jdbc.update(
                "update booking_seats set status = 'AVAILABLE', hold_id = null, held_until = null, version = version + 1 where id = :id and hold_id = :h and status = 'HELD'",
                mapOf("id" to seatId, "h" to hold.id),
            ) == 1) { "seat $seatId of hold ${hold.id} is not held" }
            jdbc.update("delete from booking_hold_seats where hold_id = :h and seat_id = :s", mapOf("h" to hold.id, "s" to seatId))
            jdbc.update("update booking_holds set seat_count = seat_count - 1 where id = :id", mapOf("id" to hold.id))
            addUsage(poolId, accountId, -1, null)
            HoldOutcome(loadHold(hold.id), changesOf(listOf(seatId)))
        }
    }

    override fun release(accountId: String, holdId: String, now: Instant): HoldOutcome {
        val owned = findOwned(accountId, holdId) ?: throw HoldNotFoundException()
        return inAccountTx(owned.poolId, accountId) {
            closeLocked(owned, HoldStatus.HELD, HoldStatus.RELEASED, SeatStatus.HELD, null) ?: HoldOutcome(loadHold(holdId), emptyList())
        }
    }

    override fun confirmFree(accountId: String, holdId: String, now: Instant): HoldOutcome {
        val owned = findOwned(accountId, holdId) ?: throw HoldNotFoundException()
        return inAccountTx(owned.poolId, accountId) {
            val hold = loadHold(holdId)
            when (hold.status) {
                HoldStatus.CONFIRMED -> return@inAccountTx HoldOutcome(hold, emptyList())
                HoldStatus.HELD -> Unit
                else -> throw HoldExpiredException(holdId)
            }
            if (!hold.expiresAt.isAfter(now)) throw HoldExpiredException(holdId)
            if (hold.seatCount == 0) throw HoldEmptyException()
            if (hold.seats.any { it.price != null }) throw PaymentRequiredException()
            check(jdbc.update(
                "update booking_holds set status = 'CONFIRMED', active_slot = null where id = :id and status = 'HELD' and expires_at > :now",
                mapOf("id" to holdId, "now" to ts(now)),
            ) == 1) { "hold $holdId left HELD under the account lock" }
            val seatIds = hold.seats.map { it.id }.sorted()
            seatIds.forEach { id ->
                check(jdbc.update(
                    "update booking_seats set status = 'SOLD', held_until = null, version = version + 1 where id = :id and hold_id = :h and status = 'HELD'",
                    mapOf("id" to id, "h" to holdId),
                ) == 1) { "seat $id of hold $holdId is not held" }
            }
            HoldOutcome(loadHold(holdId), changesOf(seatIds))
        }
    }

    override fun cancel(accountId: String, holdId: String, now: Instant): HoldOutcome {
        val owned = findOwned(accountId, holdId) ?: throw HoldNotFoundException()
        return inAccountTx(owned.poolId, accountId) {
            val hold = loadHold(holdId)
            if (hold.status == HoldStatus.CANCELED) return@inAccountTx HoldOutcome(hold, emptyList())
            // 유료(주문이 붙은) 예약은 결제 환불이 정본이다
            if (hold.status != HoldStatus.CONFIRMED || hold.confirmedOrderId != null) throw NotCancelableException()
            val pool = checkNotNull(findPool(hold.poolId))
            if (pool.closesAt != null && !now.isBefore(pool.closesAt)) throw NotCancelableException()
            closeLocked(owned, HoldStatus.CONFIRMED, HoldStatus.CANCELED, SeatStatus.SOLD, null) ?: HoldOutcome(loadHold(holdId), emptyList())
        }
    }

    override fun expire(holdId: String, now: Instant): HoldOutcome? {
        val owned = jdbc.query("select * from booking_holds where id = :id", mapOf("id" to holdId)) { rs, _ -> rs.toOwned() }.firstOrNull() ?: return null
        if (owned.status != HoldStatus.HELD || owned.expiresAt.isAfter(now)) return null
        return inAccountTx(owned.poolId, owned.accountId) {
            closeLocked(owned, HoldStatus.HELD, HoldStatus.EXPIRED, SeatStatus.HELD, now)
        }
    }

    override fun dueHolds(now: Instant, limit: Int): List<String> =
        jdbc.query(
            "select id from booking_holds where status = 'HELD' and expires_at <= :now order by expires_at limit :limit",
            mapOf("now" to ts(now), "limit" to limit),
        ) { rs, _ -> rs.getString("id") }

    override fun holdsOf(accountId: String, now: Instant): List<BookingHold> =
        jdbc.query(
            "select id from booking_holds where account_id = :a and ((status = 'HELD' and expires_at > :now) or status = 'CONFIRMED') order by created_at desc, id desc",
            mapOf("a" to accountId, "now" to ts(now)),
        ) { rs, _ -> rs.getString("id") }.map { loadHold(it) }

    override fun findHold(accountId: String, holdId: String): BookingHold? = findOwned(accountId, holdId)?.let { loadHold(holdId) }

    // ---- 내부 ------------------------------------------------------------------------------------------------------

    /** 보류를 닫고 그 자리를 모두 돌려준다 — 사용량 줄을 잠근 안에서만 부른다. 보류 줄 전이가 0줄(이미 닫힘 · 아직 안 지남)이면 null */
    private fun closeLocked(owned: OwnedHold, from: HoldStatus, to: HoldStatus, seatFrom: SeatStatus, dueBefore: Instant?): HoldOutcome? {
        val closed = jdbc.update(
            "update booking_holds set status = :to, active_slot = null where id = :id and status = :from" + if (dueBefore != null) " and expires_at <= :due" else "",
            mapOf("to" to to.name, "from" to from.name, "id" to owned.id, "due" to dueBefore?.let { ts(it) }),
        )
        if (closed == 0) return null
        val seatIds = heldSeatIds(owned.id).sorted()
        seatIds.forEach { id ->
            check(jdbc.update(
                "update booking_seats set status = 'AVAILABLE', hold_id = null, held_until = null, version = version + 1 where id = :id and hold_id = :h and status = :from",
                mapOf("id" to id, "h" to owned.id, "from" to seatFrom.name),
            ) == 1) { "seat $id of hold ${owned.id} is not ${seatFrom.name}" }
        }
        val count = jdbc.queryForObject("select seat_count from booking_holds where id = :id", mapOf("id" to owned.id), Int::class.java)!!
        addUsage(owned.poolId, owned.accountId, -count, null)
        return HoldOutcome(loadHold(owned.id), changesOf(seatIds))
    }

    /**
     * 한 계정의 쓰기 트랜잭션 - **첫 문장이 그 계정의 사용량 줄 잠금**이다. 줄을 만드는 일은 트랜잭션 밖(자동 커밋)에서 먼저 한다:
     * 트랜잭션 안에서 만들면 업무 실패로 롤백될 때 줄이 사라지고, 그 줄을 기다리던 같은 계정의 다른 요청들이 한꺼번에 깨어 서로 막아 교착한다(MySQL 실측).
     */
    private fun <T> inAccountTx(poolId: String, accountId: String, body: () -> T): T {
        jdbc.update(insertUsage, mapOf("pool_id" to poolId, "account_id" to accountId, "seats" to 0))
        @Suppress("UNCHECKED_CAST")
        return tx.execute {
            jdbc.queryForObject("select seats from booking_usage where pool_id = :p and account_id = :a for update", mapOf("p" to poolId, "a" to accountId), Int::class.java)
            body()
        } as T
    }

    /** 사용량에 [delta] 를 더한다. [limit] 이 있으면 넘는 순간 [LimitReachedException] (조건부 갱신 — 0줄) */
    private fun addUsage(poolId: String, accountId: String, delta: Int, limit: Int?) {
        if (delta == 0) return   // 값이 안 바뀌는 갱신은 MySQL 이 0줄로 셀 수 있다
        val changed = jdbc.update(
            "update booking_usage set seats = seats + :d where pool_id = :p and account_id = :a and seats + :d >= 0" + if (limit != null) " and seats + :d <= :limit" else "",
            mapOf("d" to delta, "p" to poolId, "a" to accountId, "limit" to limit),
        )
        if (changed == 0) {
            if (limit != null && delta > 0) throw LimitReachedException()
            error("booking usage of $accountId in $poolId would go below zero")
        }
    }

    private fun insertHold(poolId: String, accountId: String, expiresAt: Instant, now: Instant): OwnedHold {
        val id = ids.newId("bkh_")
        jdbc.update(
            "insert into booking_holds (id, pool_id, account_id, seat_count, status, active_slot, expires_at, created_at) values (:id, :p, :a, 0, 'HELD', 1, :exp, :now)",
            mapOf("id" to id, "p" to poolId, "a" to accountId, "exp" to ts(expiresAt), "now" to ts(now)),
        )
        return OwnedHold(id, poolId, accountId, HoldStatus.HELD, 0, expiresAt, null)
    }

    /** 이 계정의 이 풀 열린 보류. 사용량 줄을 잠근 안에서 읽는다 */
    private fun openHold(poolId: String, accountId: String): OwnedHold? =
        jdbc.query("select * from booking_holds where pool_id = :p and account_id = :a and active_slot = 1", mapOf("p" to poolId, "a" to accountId)) { rs, _ -> rs.toOwned() }.firstOrNull()

    /** 트랜잭션 밖에서 읽는다 - 남의 보류 · 없는 보류는 같은 null */
    private fun findOwned(accountId: String, holdId: String): OwnedHold? =
        jdbc.query("select * from booking_holds where id = :id and account_id = :a", mapOf("id" to holdId, "a" to accountId)) { rs, _ -> rs.toOwned() }.firstOrNull()

    private fun heldSeatIds(holdId: String): Set<String> =
        jdbc.query("select seat_id from booking_hold_seats where hold_id = :h", mapOf("h" to holdId)) { rs, _ -> rs.getString("seat_id") }.toSet()

    private fun loadHold(id: String): BookingHold {
        val seats = jdbc.query(
            "select s.* from booking_hold_seats hs join booking_seats s on s.id = hs.seat_id where hs.hold_id = :h order by s.section, s.row_label, s.number",
            mapOf("h" to id),
        ) { rs, _ -> rs.toSeat() }
        return jdbc.query("select * from booking_holds where id = :id", mapOf("id" to id)) { rs, _ -> rs.toHold(seats) }.first()
    }

    /** 자리 줄 조건부 갱신 - `AVAILABLE` 일 때만 이 보류의 것이 된다. 1줄이면 내 것 */
    private fun holdSeat(id: String, poolId: String, hold: OwnedHold): Boolean =
        jdbc.update(
            "update booking_seats set status = 'HELD', hold_id = :h, held_until = :until, version = version + 1 where id = :id and pool_id = :p and status = 'AVAILABLE'",
            mapOf("h" to hold.id, "until" to ts(hold.expiresAt), "id" to id, "p" to poolId),
        ) == 1

    /** `from → to` 조건부 갱신. 1줄이면 내 것 */
    private fun moveSeat(id: String, poolId: String, from: SeatStatus, to: SeatStatus): Boolean =
        jdbc.update(
            "update booking_seats set status = :to, version = version + 1 where id = :id and pool_id = :p and status = :from",
            mapOf("to" to to.name, "from" to from.name, "id" to id, "p" to poolId),
        ) == 1

    /** 현재 상태를 잠금 읽기로 본다(MySQL 반복 읽기에서도 최신) - 이 자리 차례에만 부른다 */
    private fun lockedSeat(id: String): BookingSeat? =
        jdbc.query("select * from booking_seats where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.toSeat() }.firstOrNull()

    private fun lockedStatus(id: String, poolId: String): SeatStatus? =
        jdbc.query("select status from booking_seats where id = :id and pool_id = :p for update", mapOf("id" to id, "p" to poolId)) { rs, _ -> SeatStatus.valueOf(rs.getString("status")) }.firstOrNull()

    /** 방금 바꾼(잠근) 자리의 지금 상태 → 사건 */
    private fun changesOf(seatIds: List<String>): List<SeatChange> =
        if (seatIds.isEmpty()) emptyList()
        else jdbc.query("select * from booking_seats where id in (:ids) order by id", mapOf("ids" to seatIds)) { rs, _ -> rs.toSeat().toChange() }

    private fun ts(value: Instant?): Any? = dialect.instantParam(value)

    private data class OwnedHold(
        val id: String,
        val poolId: String,
        val accountId: String,
        val status: HoldStatus,
        val seatCount: Int,
        val expiresAt: Instant,
        val checkoutStartedAt: Instant?,
    )

    private fun ResultSet.toOwned() = OwnedHold(
        getString("id"), getString("pool_id"), getString("account_id"), HoldStatus.valueOf(getString("status")), getInt("seat_count"),
        dialect.readInstant(this, "expires_at")!!, dialect.readInstant(this, "checkout_started_at"),
    )

    private fun ResultSet.toPool() = BookingPool(
        id = getString("id"),
        name = getString("name"),
        opensAt = dialect.readInstant(this, "opens_at")!!,
        closesAt = dialect.readInstant(this, "closes_at"),
        maxPerHold = getInt("max_per_hold"),
        perAccountLimit = getInt("per_account_limit").takeUnless { wasNull() },
        currency = getString("currency"),
        holdMinutes = getInt("hold_minutes"),
        waitingRoomId = getString("waiting_room_id"),
        status = PoolStatus.valueOf(getString("status")),
        createdAt = dialect.readInstant(this, "created_at")!!,
    )

    private fun ResultSet.toSeat() = BookingSeat(
        id = getString("id"),
        poolId = getString("pool_id"),
        section = getString("section"),
        row = getString("row_label"),
        number = getInt("number"),
        grade = getString("grade"),
        price = getLong("price_amount").takeUnless { wasNull() },
        status = SeatStatus.valueOf(getString("status")),
        holdId = getString("hold_id"),
        heldUntil = dialect.readInstant(this, "held_until"),
        version = getLong("version"),
    )

    private fun ResultSet.toHold(seats: List<BookingSeat>) = BookingHold(
        id = getString("id"),
        poolId = getString("pool_id"),
        accountId = getString("account_id"),
        status = HoldStatus.valueOf(getString("status")),
        seatCount = getInt("seat_count"),
        amount = getLong("amount").takeUnless { wasNull() },
        expiresAt = dialect.readInstant(this, "expires_at")!!,
        checkoutStartedAt = dialect.readInstant(this, "checkout_started_at"),
        confirmedOrderId = getString("confirmed_order_id"),
        seats = seats,
        createdAt = dialect.readInstant(this, "created_at")!!,
    )
}
