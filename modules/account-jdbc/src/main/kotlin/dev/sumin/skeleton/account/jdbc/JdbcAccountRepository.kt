package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountPage
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.ChangeEmailResult
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.RemoveIdentityResult
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * [AccountRepository] 를 PostgreSQL · MySQL 로 구현한다. 규칙:
 *  - 유일성(이메일 · (method, subject))은 **일반 insert 의 유니크 위반**으로 판정한다 — 먼저 조회해 보고 넣는 방식도, `insertIgnore` 의 갱신 행 수도 믿지 않는다
 *    (MySQL 의 insert-ignore 구현은 이미 있는 행에도 1 을 돌려준다). 위반은 트랜잭션을 롤백시키고 호출자에게는 false 로 보인다.
 *  - 둘 이상의 행을 건드리는 연산은 한 트랜잭션이고, 계정 행을 `for update` 로 잠가 같은 계정에 대한 동시 연산(로그인 수단 동시 해제 …)을 줄 세운다.
 *  - 시각은 항상 [SqlDialect.instantParam] / [SqlDialect.readInstant].
 */
class JdbcAccountRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
) : AccountRepository {
    override fun insert(account: Account, identities: List<Identity>): Boolean =
        try {
            tx.executeWithoutResult {
                jdbc.update(
                    """
                    insert into skeleton_accounts (id, email, email_verified, status, display_name, locale, time_zone, created_at, updated_at, last_login_at, suspended_reason, deleted_at, purge_after)
                    values (:id, :email, :verified, :status, :name, :locale, :tz, :created, :updated, :lastLogin, :suspended, :deleted, :purge)
                    """.trimIndent(),
                    MapSqlParameterSource().addValue("id", account.id).addValue("email", account.email).addValue("verified", account.emailVerified)
                        .addValue("status", account.status.name).addValue("name", account.displayName).addValue("locale", account.locale).addValue("tz", account.timeZone)
                        .addValue("created", dialect.instantParam(account.createdAt)).addValue("updated", dialect.instantParam(account.updatedAt))
                        .addValue("lastLogin", dialect.instantParam(account.lastLoginAt)).addValue("suspended", account.suspendedReason)
                        .addValue("deleted", dialect.instantParam(account.deletedAt)).addValue("purge", dialect.instantParam(account.purgeAfter)),
                )
                account.roles.forEach { jdbc.update("insert into skeleton_account_roles (account_id, role) values (:a, :r)", mapOf("a" to account.id, "r" to it)) }
                identities.forEach(::insertIdentity)
            }
            true
        } catch (_: DuplicateKeyException) {
            false
        }

    override fun findById(id: String): Account? = one("select * from skeleton_accounts where id = :v", id)

    override fun findByEmail(email: String): Account? = one("select * from skeleton_accounts where email = :v", email)

    override fun findByIdentity(method: String, subject: String): Account? =
        jdbc.query(
            "select a.* from skeleton_accounts a join skeleton_account_identities i on i.account_id = a.id where i.method = :m and i.subject = :s",
            mapOf("m" to method, "s" to subject),
        ) { rs, _ -> rs.account(emptySet()) }.firstOrNull()?.let { withRoles(listOf(it)).single() }

    private fun one(sql: String, value: String): Account? =
        jdbc.query(sql, mapOf("v" to value)) { rs, _ -> rs.account(emptySet()) }.firstOrNull()?.let { withRoles(listOf(it)).single() }

    override fun update(id: String, patch: AccountPatch, now: Instant): Account? {
        val sets = mutableListOf("updated_at = :now")
        val p = MapSqlParameterSource().addValue("id", id).addValue("now", dialect.instantParam(now))
        patch.displayName?.let { sets += "display_name = :displayName"; p.addValue("displayName", it) }
        patch.locale?.let { sets += "locale = :locale"; p.addValue("locale", it) }
        patch.timeZone?.let { sets += "time_zone = :tz"; p.addValue("tz", it) }
        patch.status?.let { sets += "status = :status"; p.addValue("status", it.name) }
        patch.lastLoginAt?.let { sets += "last_login_at = :lastLogin"; p.addValue("lastLogin", dialect.instantParam(it)) }
        if (patch.clearSuspendedReason) sets += "suspended_reason = null" else patch.suspendedReason?.let { sets += "suspended_reason = :suspended"; p.addValue("suspended", it) }
        if (patch.clearDeletion) sets += "deleted_at = null, purge_after = null" else {
            patch.deletedAt?.let { sets += "deleted_at = :deleted"; p.addValue("deleted", dialect.instantParam(it)) }
            patch.purgeAfter?.let { sets += "purge_after = :purge"; p.addValue("purge", dialect.instantParam(it)) }
        }
        val n = jdbc.update("update skeleton_accounts set ${sets.joinToString(", ")} where id = :id", p)
        return if (n == 0) null else findById(id)
    }

    override fun markEmailVerified(id: String, now: Instant): Boolean =
        tx.execute {
            val email = jdbc.query("select email from skeleton_accounts where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.getString("email") }.firstOrNull()
                ?: return@execute false
            jdbc.update(
                "update skeleton_accounts set email_verified = true, updated_at = :now, " +
                    "status = case when status = 'PENDING_VERIFICATION' then 'ACTIVE' else status end where id = :id",
                MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("id", id),
            )
            jdbc.update("update skeleton_account_identities set verified = true where account_id = :id and subject = :email", mapOf("id" to id, "email" to email))
            true
        } ?: false

    override fun changeEmail(id: String, newEmail: String, now: Instant): ChangeEmailResult =
        try {
            tx.execute {
                val row = jdbc.query("select email from skeleton_accounts where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.getString("email") }
                if (row.isEmpty()) return@execute ChangeEmailResult.NOT_FOUND
                val old = row.single()
                jdbc.update(
                    "update skeleton_accounts set email = :new, email_verified = true, updated_at = :now where id = :id",
                    MapSqlParameterSource().addValue("new", newEmail).addValue("now", dialect.instantParam(now)).addValue("id", id),
                )
                if (old != null) {
                    jdbc.update("update skeleton_account_identities set subject = :new, verified = true where account_id = :id and subject = :old", mapOf("new" to newEmail, "id" to id, "old" to old))
                }
                ChangeEmailResult.CHANGED
            } ?: ChangeEmailResult.NOT_FOUND
        } catch (_: DuplicateKeyException) {
            ChangeEmailResult.TAKEN
        }

    override fun grantRole(id: String, role: String, now: Instant): Boolean =
        try {
            jdbc.update("insert into skeleton_account_roles (account_id, role) values (:a, :r)", mapOf("a" to id, "r" to role)) == 1
        } catch (_: DataIntegrityViolationException) {
            false   // 이미 있거나(PK) 계정이 없다(FK)
        }

    override fun revokeRole(id: String, role: String, now: Instant): Boolean =
        jdbc.update("delete from skeleton_account_roles where account_id = :a and role = :r", mapOf("a" to id, "r" to role)) == 1

    override fun countActiveWithRole(role: String): Long =
        jdbc.queryForObject(
            "select count(*) from skeleton_accounts a join skeleton_account_roles r on r.account_id = a.id where r.role = :role and a.status = 'ACTIVE'",
            mapOf("role" to role), Long::class.java,
        ) ?: 0

    override fun search(email: String?, status: AccountStatus?, page: Int, size: Int): AccountPage {
        val where = mutableListOf<String>()
        val p = MapSqlParameterSource().addValue("limit", size).addValue("offset", page.toLong() * size)
        email?.let { where += "email like :pattern escape '!'"; p.addValue("pattern", "%" + it.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%") }
        status?.let { where += "status = :status"; p.addValue("status", it.name) }
        val clause = if (where.isEmpty()) "" else " where " + where.joinToString(" and ")
        val total = jdbc.queryForObject("select count(*) from skeleton_accounts$clause", p, Long::class.java) ?: 0
        val items = jdbc.query("select * from skeleton_accounts$clause order by created_at desc, id limit :limit offset :offset", p) { rs, _ -> rs.account(emptySet()) }
        return AccountPage(withRoles(items), total)
    }

    override fun dueForPurge(now: Instant, limit: Int): List<Account> =
        withRoles(
            jdbc.query(
                "select * from skeleton_accounts where status = 'DELETED' and purge_after is not null and purge_after <= :now order by purge_after limit :limit",
                MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("limit", limit),
            ) { rs, _ -> rs.account(emptySet()) },
        )

    override fun purge(id: String): Boolean = jdbc.update("delete from skeleton_accounts where id = :id", mapOf("id" to id)) == 1

    // ---- identities

    override fun addIdentity(identity: Identity): Boolean =
        try { insertIdentity(identity); true } catch (_: DataIntegrityViolationException) { false }

    private fun insertIdentity(i: Identity) {
        jdbc.update(
            """
            insert into skeleton_account_identities (id, account_id, method, subject, verified, secret, metadata, created_at, last_used_at)
            values (:id, :account, :method, :subject, :verified, :secret, :metadata, :created, :lastUsed)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", i.id).addValue("account", i.accountId).addValue("method", i.method).addValue("subject", i.subject)
                .addValue("verified", i.verified).addValue("secret", i.secret).addValue("metadata", i.metadata)
                .addValue("created", dialect.instantParam(i.createdAt)).addValue("lastUsed", dialect.instantParam(i.lastUsedAt)),
        )
    }

    override fun findIdentity(method: String, subject: String): Identity? =
        jdbc.query("select * from skeleton_account_identities where method = :m and subject = :s", mapOf("m" to method, "s" to subject)) { rs, _ -> rs.identity() }.firstOrNull()

    override fun findIdentityById(id: String): Identity? =
        jdbc.query("select * from skeleton_account_identities where id = :id", mapOf("id" to id)) { rs, _ -> rs.identity() }.firstOrNull()

    override fun identitiesOf(accountId: String): List<Identity> =
        jdbc.query("select * from skeleton_account_identities where account_id = :a order by created_at, id", mapOf("a" to accountId)) { rs, _ -> rs.identity() }

    override fun removeIdentity(accountId: String, identityId: String): Boolean =
        jdbc.update("delete from skeleton_account_identities where id = :id and account_id = :a", mapOf("id" to identityId, "a" to accountId)) == 1

    override fun removeIdentityUnlessLast(accountId: String, identityId: String, credentialMethods: Collection<String>): RemoveIdentityResult =
        tx.execute {
            // 계정 행을 잠가 같은 계정의 동시 해제를 줄 세운다 — 둘 다 "다른 게 남아 있다" 고 보고 둘 다 지우는 일이 없다
            if (jdbc.query("select id from skeleton_accounts where id = :a for update", mapOf("a" to accountId)) { rs, _ -> rs.getString(1) }.isEmpty()) return@execute RemoveIdentityResult.NOT_FOUND
            val method = jdbc.query("select method from skeleton_account_identities where id = :id and account_id = :a", mapOf("id" to identityId, "a" to accountId)) { rs, _ -> rs.getString(1) }.firstOrNull()
                ?: return@execute RemoveIdentityResult.NOT_FOUND
            val others = if (credentialMethods.isEmpty()) 0 else jdbc.queryForObject(
                "select count(*) from skeleton_account_identities where account_id = :a and id <> :id and method in (:methods)",
                mapOf("a" to accountId, "id" to identityId, "methods" to credentialMethods), Int::class.java,
            ) ?: 0
            if (method in credentialMethods && others == 0) return@execute RemoveIdentityResult.LAST
            jdbc.update("delete from skeleton_account_identities where id = :id", mapOf("id" to identityId))
            RemoveIdentityResult.REMOVED
        } ?: RemoveIdentityResult.NOT_FOUND

    override fun updateIdentitySecret(identityId: String, secret: String?): Boolean =
        jdbc.update("update skeleton_account_identities set secret = :s where id = :id", MapSqlParameterSource().addValue("s", secret).addValue("id", identityId)) == 1

    override fun touchIdentity(identityId: String, now: Instant) {
        jdbc.update("update skeleton_account_identities set last_used_at = :now where id = :id", MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("id", identityId))
    }

    // ---- mapping

    private fun withRoles(accounts: List<Account>): List<Account> {
        if (accounts.isEmpty()) return accounts
        val roles = HashMap<String, MutableSet<String>>()
        jdbc.query("select account_id, role from skeleton_account_roles where account_id in (:ids)", mapOf("ids" to accounts.map { it.id })) { rs ->
            roles.getOrPut(rs.getString("account_id")) { linkedSetOf() }.add(rs.getString("role"))
        }
        return accounts.map { it.copy(roles = roles[it.id] ?: emptySet()) }
    }

    private fun ResultSet.account(roles: Set<String>) = Account(
        id = getString("id"), email = getString("email"), emailVerified = getBoolean("email_verified"), status = AccountStatus.valueOf(getString("status")), roles = roles,
        displayName = getString("display_name"), locale = getString("locale"), timeZone = getString("time_zone"),
        createdAt = dialect.readInstant(this, "created_at")!!, updatedAt = dialect.readInstant(this, "updated_at")!!, lastLoginAt = dialect.readInstant(this, "last_login_at"),
        suspendedReason = getString("suspended_reason"), deletedAt = dialect.readInstant(this, "deleted_at"), purgeAfter = dialect.readInstant(this, "purge_after"),
    )

    private fun ResultSet.identity() = Identity(
        id = getString("id"), accountId = getString("account_id"), method = getString("method"), subject = getString("subject"), verified = getBoolean("verified"),
        secret = getString("secret"), metadata = getString("metadata"), createdAt = dialect.readInstant(this, "created_at")!!, lastUsedAt = dialect.readInstant(this, "last_used_at"),
    )
}
