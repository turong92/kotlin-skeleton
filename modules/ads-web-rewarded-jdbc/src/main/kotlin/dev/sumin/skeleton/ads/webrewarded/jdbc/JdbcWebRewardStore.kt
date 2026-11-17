package dev.sumin.skeleton.ads.webrewarded.jdbc

import dev.sumin.skeleton.ads.webrewarded.WebRewardScope
import dev.sumin.skeleton.ads.webrewarded.WebRewardSession
import dev.sumin.skeleton.ads.webrewarded.WebRewardStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * `ads_web_rewarded_sessions` · `ads_web_rewarded_daily_counts`. 같은 세션의 보상 요청은 **세션 줄을 잠그고**(`select … for update`) 직렬로 돈다 —
 * 두 번째는 첫째가 커밋한 뒤의 줄을 읽어 「이미 보상함」을 본다. 하루 상한은 「없으면 0 줄 만들기(insert ignore) → `granted < max` 일 때만 +1」 한 문장이라
 * 동시 요청이 max 를 못 넘긴다(갱신 행 수 1 이 곧 통과). 토큰은 조건 걸린 갱신 한 문장(갱신 행 수 1 이 곧 한 번의 성공)이다.
 * 예외는 [inTransaction] 밖으로 그대로 나가 안의 쓰기가 전부 되돌아간다.
 */
class JdbcWebRewardStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
) : WebRewardStore {
    private val insertCount = dialect.insertIgnore("ads_web_rewarded_daily_counts", listOf("scope", "subject", "day_start", "granted"), listOf("scope", "subject", "day_start"))

    @Suppress("UNCHECKED_CAST")
    override fun <T> inTransaction(block: () -> T): T = tx.execute { block() } as T

    override fun insert(session: WebRewardSession) {
        jdbc.update(
            "insert into ads_web_rewarded_sessions (id, account_id, purpose, client_key, opened_at, expires_at) values (:id, :account, :purpose, :client, :opened, :expires)",
            mapOf(
                "id" to session.id, "account" to session.accountId, "purpose" to session.purpose, "client" to session.clientKey,
                "opened" to dialect.instantParam(session.openedAt), "expires" to dialect.instantParam(session.expiresAt),
            ),
        )
    }

    override fun lockOwned(sessionId: String, accountId: String): WebRewardSession? =
        jdbc.query("$SELECT where id = :id and account_id = :a for update", mapOf("id" to sessionId, "a" to accountId), mapper).firstOrNull()

    override fun grantedOn(scope: WebRewardScope, subject: String, dayStart: Instant): Long =
        jdbc.query(
            "select granted from ads_web_rewarded_daily_counts where scope = :s and subject = :sub and day_start = :d",
            mapOf("s" to scope.name, "sub" to subject, "d" to dialect.instantParam(dayStart)),
        ) { rs, _ -> rs.getLong(1) }.firstOrNull() ?: 0L

    override fun incrementWithin(scope: WebRewardScope, subject: String, dayStart: Instant, max: Long): Boolean {
        val key = mapOf("scope" to scope.name, "subject" to subject, "day_start" to dialect.instantParam(dayStart))
        jdbc.update(insertCount, key + ("granted" to 0L))
        return jdbc.update(
            "update ads_web_rewarded_daily_counts set granted = granted + 1 where scope = :scope and subject = :subject and day_start = :day_start and granted < :max",
            key + ("max" to max),
        ) == 1
    }

    override fun markGranted(sessionId: String, grantedAt: Instant, rewardType: String?, rewardRef: String?, tokenHash: String?, tokenExpiresAt: Instant?) {
        jdbc.update(
            "update ads_web_rewarded_sessions set granted_at = :at, reward_type = :type, reward_ref = :ref, token_hash = :hash, token_expires_at = :texp where id = :id",
            mapOf(
                "at" to dialect.instantParam(grantedAt), "type" to rewardType, "ref" to rewardRef, "hash" to tokenHash,
                "texp" to dialect.instantParam(tokenExpiresAt), "id" to sessionId,
            ),
        )
    }

    override fun redeem(tokenHash: String, accountId: String, purpose: String, now: Instant): WebRewardSession? = inTransaction {
        val claimed = jdbc.update(
            "update ads_web_rewarded_sessions set redeemed_at = :now where token_hash = :h and account_id = :a and purpose = :p and redeemed_at is null and token_expires_at > :now",
            mapOf("now" to dialect.instantParam(now), "h" to tokenHash, "a" to accountId, "p" to purpose),
        )
        if (claimed != 1) null else jdbc.query("$SELECT where token_hash = :h", mapOf("h" to tokenHash), mapper).firstOrNull()
    }

    override fun eraseAccount(accountId: String) {
        jdbc.update("delete from ads_web_rewarded_sessions where account_id = :a", mapOf("a" to accountId))
        jdbc.update("delete from ads_web_rewarded_daily_counts where scope = 'ACCOUNT' and subject = :a", mapOf("a" to accountId))
    }

    private val mapper = RowMapper<WebRewardSession> { rs: ResultSet, _ ->
        WebRewardSession(
            id = rs.getString("id"), accountId = rs.getString("account_id"), purpose = rs.getString("purpose"), clientKey = rs.getString("client_key"),
            openedAt = dialect.readInstant(rs, "opened_at")!!, expiresAt = dialect.readInstant(rs, "expires_at")!!, grantedAt = dialect.readInstant(rs, "granted_at"),
            rewardType = rs.getString("reward_type"), rewardRef = rs.getString("reward_ref"),
            tokenExpiresAt = dialect.readInstant(rs, "token_expires_at"), redeemedAt = dialect.readInstant(rs, "redeemed_at"),
        )
    }

    private companion object {
        const val SELECT = "select id, account_id, purpose, client_key, opened_at, expires_at, granted_at, reward_type, reward_ref, token_expires_at, redeemed_at from ads_web_rewarded_sessions"
    }
}
