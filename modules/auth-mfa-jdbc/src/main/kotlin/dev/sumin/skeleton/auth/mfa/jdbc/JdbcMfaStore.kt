package dev.sumin.skeleton.auth.mfa.jdbc

import dev.sumin.skeleton.auth.mfa.MfaStore
import dev.sumin.skeleton.auth.mfa.MfaTotpRecord
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 표 둘(`mfa_totp` · `mfa_recovery_codes`). 상태 전이는 모두 **한 문장의 조건부 갱신**이라 동시에 와도 한 요청만 이긴다 -
 * 대기 → 비밀 옮김은 `pending_secret_enc = :expected and pending_expires_at > :now`, 걸음은 `last_used_step is null or last_used_step < :step`,
 * 복구 코드는 `used_at is null`. 두 표를 함께 건드리는 일(첫 등록의 복구 코드 · 지우기)만 한 트랜잭션이다.
 */
class JdbcMfaStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val ids: SortableIds = SortableIds(),
) : MfaStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun find(accountId: String): MfaTotpRecord? =
        jdbc.query("select * from mfa_totp where account_id = :id", mapOf("id" to accountId)) { rs, _ -> rs.toRecord() }.firstOrNull()

    override fun startEnrollment(accountId: String, pendingSecretEnc: String, expiresAt: Instant, now: Instant) {
        val params = MapSqlParameterSource().addValue("account_id", accountId).addValue("pending_secret_enc", pendingSecretEnc)
            .addValue("pending_expires_at", instant(expiresAt)).addValue("updated_at", instant(now))
        val update = "update mfa_totp set pending_secret_enc = :pending_secret_enc, pending_expires_at = :pending_expires_at, updated_at = :updated_at where account_id = :account_id"
        if (jdbc.update(update, params) == 0) {
            // 첫 등록 - 두 요청이 겹쳐 한쪽의 insert 가 무시돼도(유니크) 뒤따르는 update 가 자기 값을 쓴다
            jdbc.update(dialect.insertIgnore("mfa_totp", listOf("account_id", "pending_secret_enc", "pending_expires_at", "updated_at"), listOf("account_id")), params)
            jdbc.update(update, params)
        }
    }

    override fun activate(accountId: String, expectedPendingEnc: String, step: Long, now: Instant, recoveryCodeHashes: List<String>?): Boolean =
        transactions.execute {
            // 대입은 왼쪽부터 평가된다(MySQL) - secret_enc 가 pending 을 먼저 읽고, 그다음에 pending 을 비운다. PostgreSQL 은 모두 옛 값을 읽는다
            val moved = jdbc.update(
                "update mfa_totp set secret_enc = pending_secret_enc, enabled_at = :now, last_used_step = :step, pending_secret_enc = null, pending_expires_at = null, updated_at = :now " +
                    "where account_id = :id and pending_secret_enc = :expected and pending_expires_at > :now",
                MapSqlParameterSource().addValue("id", accountId).addValue("expected", expectedPendingEnc).addValue("step", step).addValue("now", instant(now)),
            ) == 1
            if (moved && recoveryCodeHashes != null) replaceCodes(accountId, recoveryCodeHashes, now)
            moved
        }!!

    override fun useStep(accountId: String, step: Long, now: Instant): Boolean =
        jdbc.update(
            "update mfa_totp set last_used_step = :step, updated_at = :now where account_id = :id and secret_enc is not null and (last_used_step is null or last_used_step < :step)",
            MapSqlParameterSource().addValue("id", accountId).addValue("step", step).addValue("now", instant(now)),
        ) == 1

    override fun replaceRecoveryCodes(accountId: String, hashes: List<String>, now: Instant) {
        transactions.executeWithoutResult { replaceCodes(accountId, hashes, now) }
    }

    override fun useRecoveryCode(accountId: String, hash: String, now: Instant): Boolean =
        jdbc.update(
            "update mfa_recovery_codes set used_at = :now where account_id = :id and code_hash = :hash and used_at is null",
            MapSqlParameterSource().addValue("id", accountId).addValue("hash", hash).addValue("now", instant(now)),
        ) == 1

    override fun recoveryCodesRemaining(accountId: String): Int =
        jdbc.queryForObject("select count(*) from mfa_recovery_codes where account_id = :id and used_at is null", mapOf("id" to accountId), Int::class.java)!!

    override fun deleteAll(accountId: String) {
        transactions.executeWithoutResult {
            jdbc.update("delete from mfa_recovery_codes where account_id = :id", mapOf("id" to accountId))
            jdbc.update("delete from mfa_totp where account_id = :id", mapOf("id" to accountId))
        }
    }

    private fun replaceCodes(accountId: String, hashes: List<String>, now: Instant) {
        jdbc.update("delete from mfa_recovery_codes where account_id = :id", mapOf("id" to accountId))
        if (hashes.isEmpty()) return
        jdbc.batchUpdate(
            "insert into mfa_recovery_codes (id, account_id, code_hash, created_at) values (:id, :account_id, :code_hash, :created_at)",
            hashes.map {
                MapSqlParameterSource().addValue("id", ids.newId(ID_PREFIX)).addValue("account_id", accountId).addValue("code_hash", it).addValue("created_at", instant(now))
            }.toTypedArray(),
        )
    }

    private fun instant(value: Instant?): Any? = dialect.instantParam(value)

    private fun ResultSet.toRecord() = MfaTotpRecord(
        accountId = getString("account_id"),
        secretEnc = getString("secret_enc"),
        enabledAt = dialect.readInstant(this, "enabled_at"),
        lastUsedStep = getLong("last_used_step").takeUnless { wasNull() },
        pendingSecretEnc = getString("pending_secret_enc"),
        pendingExpiresAt = dialect.readInstant(this, "pending_expires_at"),
        updatedAt = dialect.readInstant(this, "updated_at")!!,
    )

    private companion object {
        const val ID_PREFIX = "mfr_"
    }
}
