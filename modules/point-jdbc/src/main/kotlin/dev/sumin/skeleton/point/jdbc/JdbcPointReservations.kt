package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.point.PointEntry
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointInsufficientException
import dev.sumin.skeleton.point.PointReservation
import dev.sumin.skeleton.point.PointReservationExceededException
import dev.sumin.skeleton.point.PointReservationNotFoundException
import dev.sumin.skeleton.point.PointReservationNotHeldException
import dev.sumin.skeleton.point.PointReservationStatus
import dev.sumin.skeleton.point.PointSourceConflictException
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * 잡아 두기(`point_reservations`) - [JdbcPointStore] 와 같은 규칙으로 **계정의 잔액 줄을 잠그고** 직렬로 돈다(`for update`).
 * 잡으면 `spend` 처럼 묶음에서 덜어 내 잔액에서 빼고 할당(`point_spend_allocations`, 키 = 건 id)을 남긴다 - 그래서 잡힌 몫은 묶음 소멸이 건드리지 못하고(확정은 늘 된다), 풀면 원래 묶음으로 돌아간다.
 * 건의 상태는 `HELD` 에서 **조건부 갱신**(`where status = 'HELD'`)으로 한 번만 바뀐다 - 확정 · 풀기 · 만료가 겹쳐도(다른 인스턴스 포함) 하나만 이긴다.
 * 건은 키로 찾으므로 잠글 계정을 알려고 잠금 없이 먼저 한 번 읽고, 잠근 뒤 다시 읽어 그 값으로 결정한다. 실패 예외는 트랜잭션 밖에서 던진다(호출자 트랜잭션을 롤백 전용으로 만들지 않는다).
 */
internal class JdbcPointReservations(
    private val store: JdbcPointStore,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
    private val ids: SortableIds,
) {
    fun reserve(accountId: String, amount: Long, sourceType: String, sourceRef: String, holdUntil: Instant, now: Instant): PointReservation {
        val outcome = tx.execute {
            val balance = store.lockBalance(accountId, now)
            find(sourceType, sourceRef)?.let { found ->
                check(found.accountId == accountId) { "point source $sourceType/$sourceRef belongs to another account" }
                return@execute if (found.amount == amount) Done(found) else Conflict
            }
            if (store.findBySource(sourceType, sourceRef) != null) return@execute Conflict   // 원장 줄이 이미 쓴 키
            val available = balance - store.dueRemaining(accountId, now)
            if (amount > available) return@execute Short(available)
            store.expireAccount(accountId, now)
            val taken = store.consume(accountId, amount)
            val id = ids.newId("pt_")
            jdbc.update(
                "insert into point_reservations (id, account_id, amount, captured_amount, status, source_type, source_ref, hold_until, created_at, settled_at) " +
                    "values (:id, :a, :amount, null, 'HELD', :t, :r, :hold, :now, null)",
                mapOf("id" to id, "a" to accountId, "amount" to amount, "t" to sourceType, "r" to sourceRef, "hold" to dialect.instantParam(holdUntil), "now" to dialect.instantParam(now)),
            )
            taken.forEach { (bundle, take) ->
                jdbc.update("insert into point_spend_allocations (spend_entry_id, bundle_id, amount, restored) values (:s, :b, :amount, 0)", mapOf("s" to id, "b" to bundle, "amount" to take))
            }
            store.changeBalance(accountId, -amount, now)
            Done(PointReservation(id, accountId, amount, null, PointReservationStatus.HELD, sourceType, sourceRef, holdUntil, now, null))
        }
        return when (outcome) {
            is Done -> outcome.reservation
            is Short -> throw PointInsufficientException(amount, outcome.available)
            else -> throw PointSourceConflictException()
        }
    }

    fun capture(sourceType: String, sourceRef: String, amount: Long?, now: Instant): PointEntry {
        val seen = find(sourceType, sourceRef) ?: throw PointReservationNotFoundException()
        val outcome = tx.execute {
            store.lockBalance(seen.accountId, now)
            val held = find(sourceType, sourceRef) ?: return@execute Gone
            when (held.status) {
                PointReservationStatus.CAPTURED ->
                    if (amount == null || amount == held.capturedAmount) Captured(checkNotNull(store.findBySource(sourceType, sourceRef)) { "captured reservation has no ledger line" }) else Conflict
                PointReservationStatus.RELEASED, PointReservationStatus.EXPIRED -> NotHeld(held.status)
                PointReservationStatus.HELD -> {
                    val target = amount ?: held.amount
                    if (target > held.amount) return@execute Exceeded(target, held.amount)
                    if (store.findBySource(sourceType, sourceRef) != null) return@execute Conflict   // 잡은 뒤 같은 키를 원장 줄이 썼다 - 이 건은 풀려야 한다
                    settle(held.id, PointReservationStatus.CAPTURED, target, now)
                    // 줄 id 는 건 id 와 같다 - 잡을 때 남긴 할당이 그대로 이 차감의 할당이 된다(사용 취소가 원래 묶음에 돌려놓는다)
                    val entry = PointEntry(held.id, held.accountId, -target, PointEntryKind.SPEND, 0, null, sourceType, sourceRef, null, now)
                    store.insert(entry)
                    if (target < held.amount) giveBack(held, held.amount - target, now)
                    Captured(entry)
                }
            }
        }
        return when (outcome) {
            is Captured -> outcome.entry
            is Exceeded -> throw PointReservationExceededException(outcome.requested, outcome.held)
            is NotHeld -> throw PointReservationNotHeldException(outcome.status)
            Gone -> throw PointReservationNotFoundException()
            else -> throw PointSourceConflictException()
        }
    }

    fun release(sourceType: String, sourceRef: String, now: Instant): PointReservation {
        val seen = find(sourceType, sourceRef) ?: throw PointReservationNotFoundException()
        val outcome = tx.execute {
            store.lockBalance(seen.accountId, now)
            val held = find(sourceType, sourceRef) ?: return@execute Gone
            when (held.status) {
                PointReservationStatus.HELD -> Done(close(held, PointReservationStatus.RELEASED, now))
                PointReservationStatus.RELEASED, PointReservationStatus.EXPIRED -> Done(held)
                PointReservationStatus.CAPTURED -> NotHeld(held.status)
            }
        }
        return when (outcome) {
            is Done -> outcome.reservation
            is NotHeld -> throw PointReservationNotHeldException(outcome.status)
            else -> throw PointReservationNotFoundException()
        }
    }

    fun expire(now: Instant, batch: Int): Int {
        var expired = 0
        while (true) {
            val due = jdbc.query(
                "select id, account_id, source_type, source_ref from point_reservations where status = 'HELD' and hold_until <= :now order by hold_until, id limit $batch",
                mapOf("now" to dialect.instantParam(now)),
            ) { rs, _ -> listOf(rs.getString(2), rs.getString(3), rs.getString(4)) }
            if (due.isEmpty()) return expired
            due.forEach { (account, type, ref) ->
                expired += tx.execute {
                    store.lockBalance(account, now)
                    // 잠근 뒤 다시 읽는다 - 그 사이 확정 · 풀기가 이겼으면 건드리지 않는다
                    val held = find(type, ref)
                    if (held != null && held.status == PointReservationStatus.HELD && !held.holdUntil.isAfter(now)) { close(held, PointReservationStatus.EXPIRED, now); 1 } else 0
                } ?: 0
            }
        }
    }

    fun find(sourceType: String, sourceRef: String): PointReservation? =
        jdbc.query("$SELECT where source_type = :t and source_ref = :r", mapOf("t" to sourceType, "r" to sourceRef), rows).firstOrNull()

    fun heldSum(accountId: String): Long =
        jdbc.queryForObject("select coalesce(sum(amount), 0) from point_reservations where account_id = :a and status = 'HELD'", mapOf("a" to accountId), Long::class.java) ?: 0L

    fun erase(accountId: String) {
        jdbc.update("delete from point_spend_allocations where spend_entry_id in (select id from point_reservations where account_id = :a)", mapOf("a" to accountId))
        jdbc.update("delete from point_reservations where account_id = :a", mapOf("a" to accountId))
    }

    /** 잡힌 건 전부를 풀고 끝낸다 */
    private fun close(held: PointReservation, status: PointReservationStatus, now: Instant): PointReservation {
        settle(held.id, status, null, now)
        giveBack(held, held.amount, now)
        return held.copy(status = status, settledAt = now)
    }

    /** `HELD` 인 건만 끝낸다 - 상태가 이미 바뀌었으면(잠근 안이라 있을 수 없다) 던져서 롤백한다 */
    private fun settle(id: String, status: PointReservationStatus, captured: Long?, now: Instant) {
        val changed = jdbc.update(
            "update point_reservations set status = :status, captured_amount = :c, settled_at = :now where id = :id and status = 'HELD'",
            mapOf("status" to status.name, "c" to captured, "now" to dialect.instantParam(now), "id" to id),
        )
        check(changed == 1) { "point reservation changed under the account lock; retry" }
    }

    /**
     * 잡았던 양 가운데 [give] 를 원래 묶음으로 돌려준다(유효기간이 늦은 묶음부터, 사용 취소와 같은 순서) - 할당에서 그만큼 덜어 낸다.
     * 그 사이 유효기간이 지난 묶음 몫은 되살리지 않고 `EXPIRE` 줄로 닫는다(잔액은 이미 빠져 있으니 그대로).
     */
    private fun giveBack(held: PointReservation, give: Long, now: Instant) {
        val allocations = jdbc.query(
            "select a.bundle_id, a.amount, e.expires_at from point_spend_allocations a join point_entries e on e.id = a.bundle_id " +
                "where a.spend_entry_id = :s order by case when e.expires_at is null then 0 else 1 end, e.expires_at desc, e.created_at desc, e.id desc",
            mapOf("s" to held.id),
        ) { rs, _ -> Triple(rs.getString("bundle_id"), rs.getLong("amount"), dialect.readInstant(rs, "expires_at")) }
        var left = give
        var returned = 0L
        for ((bundle, amount, expiresAt) in allocations) {
            if (left == 0L) break
            val take = minOf(left, amount)
            val changed = if (take == amount) {
                jdbc.update("delete from point_spend_allocations where spend_entry_id = :s and bundle_id = :b and amount = :seen", mapOf("s" to held.id, "b" to bundle, "seen" to amount))
            } else {
                jdbc.update("update point_spend_allocations set amount = amount - :take where spend_entry_id = :s and bundle_id = :b and amount = :seen", mapOf("take" to take, "s" to held.id, "b" to bundle, "seen" to amount))
            }
            check(changed == 1) { "point allocation changed under the account lock; retry" }
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                store.insert(PointEntry(ids.newId("pt_"), held.accountId, -take, PointEntryKind.EXPIRE, 0, null, EXPIRE_SOURCE, "$bundle:${held.id}", null, now))
            } else {
                val grown = jdbc.update("update point_entries set remaining = remaining + :take where id = :id and remaining + :take <= delta", mapOf("take" to take, "id" to bundle))
                check(grown == 1) { "point bundle changed under the account lock; retry" }
                returned += take
            }
            left -= take
        }
        check(left == 0L) { "point allocations do not add up to the reservation; retry" }
        if (returned > 0) store.changeBalance(held.accountId, returned, now)
    }

    private val rows = RowMapper<PointReservation> { rs, _ -> reservation(rs) }

    private fun reservation(rs: ResultSet): PointReservation {
        val captured = rs.getLong("captured_amount").takeUnless { rs.wasNull() }
        return PointReservation(
            id = rs.getString("id"),
            accountId = rs.getString("account_id"),
            amount = rs.getLong("amount"),
            capturedAmount = captured,
            status = PointReservationStatus.valueOf(rs.getString("status")),
            sourceType = rs.getString("source_type"),
            sourceRef = rs.getString("source_ref"),
            holdUntil = dialect.readInstant(rs, "hold_until")!!,
            createdAt = dialect.readInstant(rs, "created_at")!!,
            settledAt = dialect.readInstant(rs, "settled_at"),
        )
    }

    private sealed interface Outcome
    private class Done(val reservation: PointReservation) : Outcome
    private class Short(val available: Long) : Outcome
    private class Captured(val entry: PointEntry) : Outcome
    private class Exceeded(val requested: Long, val held: Long) : Outcome
    private class NotHeld(val status: PointReservationStatus) : Outcome
    private data object Conflict : Outcome
    private data object Gone : Outcome

    private companion object {
        const val EXPIRE_SOURCE = "EXPIRE"
        const val SELECT = "select id, account_id, amount, captured_amount, status, source_type, source_ref, hold_until, created_at, settled_at from point_reservations"
    }
}
