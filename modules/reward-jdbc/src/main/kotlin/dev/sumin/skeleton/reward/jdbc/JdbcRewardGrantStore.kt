package dev.sumin.skeleton.reward.jdbc

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.reward.NewRewardGrant
import dev.sumin.skeleton.reward.RewardGrantRecord
import dev.sumin.skeleton.reward.RewardGrantStatus
import dev.sumin.skeleton.reward.RewardGrantStore
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * `reward_grants` — 지급 건 한 줄. 넣기는 [SqlDialect.insertIgnore](PG 에서 유니크 위반 예외는 호출자 트랜잭션 전체를 abort 시킨다)이고,
 * **넣었는지는 갱신 행 수가 아니라 내가 만든 id 가 있는지로 본다**(MySQL 의 `on duplicate key update` 는 이미 있는 행에도 1 을 돌려준다).
 * 상태 전이는 모두 `where status = …` 조건부 갱신이라 동시에 와도 한쪽만 이긴다.
 */
class JdbcRewardGrantStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val json: JsonCodec,
) : RewardGrantStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun hasOpenTransaction(): Boolean = TransactionSynchronizationManager.isActualTransactionActive()

    override fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }

    override fun insertIfAbsent(grant: NewRewardGrant): Boolean {
        jdbc.update(
            dialect.insertIgnore(TABLE, INSERT_COLUMNS, listOf("source_type", "source_key")),
            MapSqlParameterSource()
                .addValue("id", grant.id)
                .addValue("account_id", grant.accountId)
                .addValue("reward_type", grant.type)
                .addValue("params_json", json.canonicalString(json.toDocument(grant.params)))
                .addValue("source_type", grant.sourceType)
                .addValue("source_key", grant.sourceKey)
                .addValue("status", RewardGrantStatus.PENDING.name)
                .addValue("created_at", dialect.instantParam(grant.createdAt)),
        )
        return jdbc.queryForObject("select count(*) from $TABLE where id = :id", mapOf("id" to grant.id), Long::class.java) == 1L
    }

    override fun lockPending(id: String): RewardGrantView? =
        jdbc.query("select * from $TABLE where id = :id and status = 'PENDING' for update", mapOf("id" to id)) { rs, _ -> rs.toRecord() }
            .firstOrNull()?.let { RewardGrantView(it.id, it.accountId, it.type, it.params, it.sourceType, it.sourceKey) }

    override fun markIssued(id: String, resultRef: String, at: Instant) {
        jdbc.update(
            "update $TABLE set status = 'ISSUED', result_ref = :ref, last_error = null, issued_at = :at where id = :id",
            mapOf("id" to id, "ref" to resultRef, "at" to dialect.instantParam(at)),
        )
    }

    override fun markFailed(id: String, error: String): Boolean =
        jdbc.update("update $TABLE set status = 'FAILED', last_error = :error where id = :id and status = 'PENDING'", mapOf("id" to id, "error" to error.take(MAX_ERROR))) == 1

    override fun reopen(id: String): Boolean =
        jdbc.update("update $TABLE set status = 'PENDING', last_error = null where id = :id and status = 'FAILED'", mapOf("id" to id)) == 1

    override fun cancel(id: String): Boolean =
        jdbc.update("update $TABLE set status = 'CANCELED' where id = :id and status = 'PENDING'", mapOf("id" to id)) == 1

    override fun find(id: String): RewardGrantRecord? =
        jdbc.query("select * from $TABLE where id = :id", mapOf("id" to id)) { rs, _ -> rs.toRecord() }.firstOrNull()

    override fun page(status: RewardGrantStatus?, before: String?, limit: Int): List<RewardGrantRecord> =
        jdbc.query(
            "select * from $TABLE where (:status is null or status = :status) and (:before is null or id < :before) order by id desc limit :limit",
            MapSqlParameterSource().addValue("status", status?.name, java.sql.Types.VARCHAR).addValue("before", before, java.sql.Types.VARCHAR).addValue("limit", limit),
        ) { rs, _ -> rs.toRecord() }

    override fun deleteByAccount(accountId: String): Int = jdbc.update("delete from $TABLE where account_id = :a", mapOf("a" to accountId))

    @Suppress("UNCHECKED_CAST")
    private fun ResultSet.toRecord() = RewardGrantRecord(
        id = getString("id"),
        accountId = getString("account_id"),
        type = getString("reward_type"),
        params = (json.fromDocument(json.parse(getString("params_json")), Map::class) as Map<String, Any?>).mapValues { it.value.toString() },
        sourceType = getString("source_type"),
        sourceKey = getString("source_key"),
        status = RewardGrantStatus.valueOf(getString("status")),
        resultRef = getString("result_ref"),
        lastError = getString("last_error"),
        createdAt = dialect.readInstant(this, "created_at")!!,
        issuedAt = dialect.readInstant(this, "issued_at"),
    )

    private companion object {
        const val TABLE = "reward_grants"
        const val MAX_ERROR = 200
        val INSERT_COLUMNS = listOf("id", "account_id", "reward_type", "params_json", "source_type", "source_key", "status", "created_at")
    }
}
