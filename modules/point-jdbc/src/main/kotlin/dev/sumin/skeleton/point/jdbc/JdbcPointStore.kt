package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.point.PointBalance
import dev.sumin.skeleton.point.PointCursor
import dev.sumin.skeleton.point.PointEntry
import dev.sumin.skeleton.point.PointEntryKind
import dev.sumin.skeleton.point.PointExpiring
import dev.sumin.skeleton.point.PointInsufficientException
import dev.sumin.skeleton.point.PointStore
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * `point_balances` · `point_entries` — 같은 계정의 쓰기는 **잔액 줄을 잠그고**(`select … for update`) 직렬로 돈다. 잔액 = 모든 적립 묶음의 `remaining` 합이라는 불변식이 그 잠금 안에서만 바뀐다.
 *
 * 설계 선택:
 * - 잔액 확인은 쓰기 전에 하고 모자라면 **트랜잭션 밖에서** 던진다 - 호출자 트랜잭션이 롤백 전용이 되지 않는다(잡아서 계속할 수 있다).
 * - 항목 표는 잠금 읽기를 하지 않는다: 새 키는 인덱스 끝 틈에 쌓이므로 계정이 다른 동시 쓰기끼리 틈 잠금으로 교착한다(MySQL). 같은 계정 직렬화는 잔액 줄 잠금이, 같은 키 중복은 유일 인덱스가 막는다.
 *   호출자의 바깥 트랜잭션이 MySQL 반복 읽기라 낡은 스냅샷을 쥐고 있어도 돈이 틀어지지 않는다 - 갱신은 모두 조건(`remaining` 이 읽은 값) 걸린 문장이고, 어긋나면 던져서 롤백한다(다시 시도하면 된다).
 */
class JdbcPointStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
    private val ids: SortableIds = SortableIds.system,
) : PointStore {
    private val insertBalance = dialect.insertIgnore("point_balances", listOf("account_id", "balance", "updated_at"), listOf("account_id"))

    override fun earn(
        accountId: String,
        amount: Long,
        expiresAt: Instant?,
        kind: PointEntryKind,
        sourceType: String,
        sourceRef: String,
        reason: String?,
        now: Instant,
    ): PointEntry? {
        require(kind == PointEntryKind.EARN || kind == PointEntryKind.ADJUST) { "an addition is EARN or ADJUST" }
        return tx.execute {
            lockBalance(accountId, now)
            if (existing(accountId, sourceType, sourceRef) != null) return@execute null
            val entry = PointEntry(ids.newId("pt_"), accountId, amount, kind, amount, expiresAt, sourceType, sourceRef, reason, now)
            insert(entry)
            changeBalance(accountId, amount, now)
            entry
        }
    }

    override fun spend(
        accountId: String,
        amount: Long,
        kind: PointEntryKind,
        sourceType: String,
        sourceRef: String,
        reason: String?,
        now: Instant,
    ): PointEntry {
        require(kind == PointEntryKind.SPEND || kind == PointEntryKind.ADJUST) { "a deduction is SPEND or ADJUST" }
        val outcome = tx.execute {
            val balance = lockBalance(accountId, now)
            existing(accountId, sourceType, sourceRef)?.let { return@execute Spent(it) }
            val available = balance - dueRemaining(accountId, now)
            if (amount > available) return@execute Short(available)
            expireAccount(accountId, now)
            consume(accountId, amount)
            val entry = PointEntry(ids.newId("pt_"), accountId, -amount, kind, 0, null, sourceType, sourceRef, reason, now)
            insert(entry)
            changeBalance(accountId, -amount, now)
            Spent(entry)
        }
        return when (outcome) {
            is Spent -> outcome.entry
            is Short -> throw PointInsufficientException(amount, outcome.available)
        }
    }

    override fun balance(accountId: String, now: Instant, soonBefore: Instant): PointBalance {
        val stored = jdbc.query("select balance from point_balances where account_id = :a", mapOf("a" to accountId)) { rs, _ -> rs.getLong(1) }.firstOrNull() ?: 0L
        val usable = (stored - dueRemaining(accountId, now)).coerceAtLeast(0)
        val soon = jdbc.queryForObject(
            "select coalesce(sum(remaining), 0) from point_entries where account_id = :a and remaining > 0 and expires_at is not null and expires_at > :now and expires_at <= :before",
            mapOf("a" to accountId, "now" to dialect.instantParam(now), "before" to dialect.instantParam(soonBefore)),
            Long::class.java,
        ) ?: 0L
        return PointBalance(usable, if (soon > 0) PointExpiring(soon, soonBefore) else null)
    }

    override fun history(accountId: String, after: PointCursor?, limit: Int): List<PointEntry> {
        val params = mutableMapOf<String, Any?>("a" to accountId, "limit" to limit)
        val seek = if (after == null) "" else {
            params["c"] = dialect.instantParam(after.createdAt)
            params["id"] = after.id
            "and (created_at < :c or (created_at = :c and id < :id))"
        }
        return jdbc.query("$SELECT where account_id = :a $seek order by created_at desc, id desc limit :limit", params, rows)
    }

    override fun findBySource(sourceType: String, sourceRef: String): PointEntry? =
        jdbc.query("$SELECT where source_type = :t and source_ref = :r", mapOf("t" to sourceType, "r" to sourceRef), rows).firstOrNull()

    override fun expireDue(now: Instant): Int {
        var closed = 0
        while (true) {
            val accounts = jdbc.query(
                "select distinct account_id from point_entries where remaining > 0 and expires_at is not null and expires_at <= :now limit $BATCH",
                mapOf("now" to dialect.instantParam(now)),
            ) { rs, _ -> rs.getString(1) }
            if (accounts.isEmpty()) return closed
            val before = closed
            accounts.forEach { account -> closed += tx.execute { lockBalance(account, now); expireAccount(account, now) } }
            if (closed == before) return closed   // 읽은 것을 못 닫았다 - 같은 줄을 돌며 멈추지 않게
        }
    }

    override fun eraseAccount(accountId: String) {
        tx.executeWithoutResult {
            jdbc.update("delete from point_entries where account_id = :a", mapOf("a" to accountId))
            jdbc.update("delete from point_balances where account_id = :a", mapOf("a" to accountId))
        }
    }

    /** 잔액 줄을 (없으면 만들고) 잠그고 잠근 뒤의 잔액을 돌려준다 - MySQL 도 잠금 읽기는 최신 값을 본다 */
    private fun lockBalance(accountId: String, now: Instant): Long {
        jdbc.update(insertBalance, mapOf("account_id" to accountId, "balance" to 0L, "updated_at" to dialect.instantParam(now)))
        return jdbc.queryForObject("select balance from point_balances where account_id = :a for update", mapOf("a" to accountId), Long::class.java)!!
    }

    private fun changeBalance(accountId: String, delta: Long, now: Instant) {
        val changed = jdbc.update(
            "update point_balances set balance = balance + :delta, updated_at = :now where account_id = :a and balance + :delta >= 0",
            mapOf("a" to accountId, "delta" to delta, "now" to dialect.instantParam(now)),
        )
        check(changed == 1) { "point balance would go below zero" }
    }

    /** 이 키가 이미 있으면 그 줄. 다른 계정의 줄이면 던진다 */
    private fun existing(accountId: String, sourceType: String, sourceRef: String): PointEntry? {
        val found = findBySource(sourceType, sourceRef) ?: return null
        check(found.accountId == accountId) { "point source $sourceType/$sourceRef belongs to another account" }
        return found
    }

    private fun dueRemaining(accountId: String, now: Instant): Long =
        jdbc.queryForObject(
            "select coalesce(sum(remaining), 0) from point_entries where account_id = :a and remaining > 0 and expires_at is not null and expires_at <= :now",
            mapOf("a" to accountId, "now" to dialect.instantParam(now)),
            Long::class.java,
        ) ?: 0L

    /** 유효기간이 지난 이 계정의 묶음을 닫는다 - 잔액 줄을 잠근 안에서 부른다. 만든 `EXPIRE` 줄 수 */
    private fun expireAccount(accountId: String, now: Instant): Int {
        val due = jdbc.query(
            "select id, remaining from point_entries where account_id = :a and remaining > 0 and expires_at is not null and expires_at <= :now order by expires_at, id",
            mapOf("a" to accountId, "now" to dialect.instantParam(now)),
        ) { rs, _ -> rs.getString("id") to rs.getLong("remaining") }
        due.forEach { (bundle, remaining) ->
            zero(bundle, remaining)
            insert(PointEntry(ids.newId("pt_"), accountId, -remaining, PointEntryKind.EXPIRE, 0, null, EXPIRE_SOURCE, bundle, null, now))
            changeBalance(accountId, -remaining, now)
        }
        return due.size
    }

    /** 소멸이 가까운 묶음부터(유효기간 없는 것은 맨 뒤) [amount] 만큼 덜어 낸다 */
    private fun consume(accountId: String, amount: Long) {
        val bundles = jdbc.query(
            "select id, remaining from point_entries where account_id = :a and remaining > 0 " +
                "order by case when expires_at is null then 1 else 0 end, expires_at, created_at, id",
            mapOf("a" to accountId),
        ) { rs, _ -> rs.getString("id") to rs.getLong("remaining") }
        var left = amount
        for ((bundle, remaining) in bundles) {
            if (left == 0L) break
            val take = minOf(left, remaining)
            val changed = jdbc.update(
                "update point_entries set remaining = remaining - :take where id = :id and remaining = :seen",
                mapOf("take" to take, "id" to bundle, "seen" to remaining),
            )
            check(changed == 1) { "point bundle changed under the account lock; retry" }
            left -= take
        }
        check(left == 0L) { "point bundles do not add up to the balance; retry" }
    }

    private fun zero(bundle: String, seen: Long) {
        val changed = jdbc.update("update point_entries set remaining = 0 where id = :id and remaining = :seen", mapOf("id" to bundle, "seen" to seen))
        check(changed == 1) { "point bundle changed under the account lock; retry" }
    }

    private fun insert(e: PointEntry) {
        jdbc.update(
            "insert into point_entries (id, account_id, delta, kind, remaining, expires_at, source_type, source_ref, reason, created_at) " +
                "values (:id, :account_id, :delta, :kind, :remaining, :expires_at, :source_type, :source_ref, :reason, :created_at)",
            mapOf(
                "id" to e.id, "account_id" to e.accountId, "delta" to e.delta, "kind" to e.kind.name, "remaining" to e.remaining,
                "expires_at" to dialect.instantParam(e.expiresAt), "source_type" to e.sourceType, "source_ref" to e.sourceRef,
                "reason" to e.reason, "created_at" to dialect.instantParam(e.createdAt),
            ),
        )
    }

    private val rows = RowMapper<PointEntry> { rs, _ -> entry(rs) }

    private fun entry(rs: ResultSet) = PointEntry(
        id = rs.getString("id"),
        accountId = rs.getString("account_id"),
        delta = rs.getLong("delta"),
        kind = PointEntryKind.valueOf(rs.getString("kind")),
        remaining = rs.getLong("remaining"),
        expiresAt = dialect.readInstant(rs, "expires_at"),
        sourceType = rs.getString("source_type"),
        sourceRef = rs.getString("source_ref"),
        reason = rs.getString("reason"),
        createdAt = dialect.readInstant(rs, "created_at")!!,
    )

    private sealed interface SpendOutcome
    private class Spent(val entry: PointEntry) : SpendOutcome
    private class Short(val available: Long) : SpendOutcome

    private companion object {
        const val EXPIRE_SOURCE = "EXPIRE"
        const val BATCH = 200
        const val SELECT = "select id, account_id, delta, kind, remaining, expires_at, source_type, source_ref, reason, created_at from point_entries"
    }
}
