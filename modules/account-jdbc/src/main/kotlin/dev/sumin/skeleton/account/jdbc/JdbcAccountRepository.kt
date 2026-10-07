package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AddIdentityResult
import dev.sumin.skeleton.account.AccountPage
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.ChangeEmailResult
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.GuardedResult
import dev.sumin.skeleton.account.MailboxProof
import dev.sumin.skeleton.account.SignInMethods
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
                    insert into accounts (id, email, email_verified, status, display_name, locale, time_zone, created_at, updated_at, last_login_at, suspended_reason, deleted_at, purge_after, erased_at)
                    values (:id, :email, :verified, :status, :name, :locale, :tz, :created, :updated, :lastLogin, :suspended, :deleted, :purge, :erased)
                    """.trimIndent(),
                    MapSqlParameterSource().addValue("id", account.id).addValue("email", account.email).addValue("verified", account.emailVerified)
                        .addValue("status", account.status.name).addValue("name", account.displayName).addValue("locale", account.locale).addValue("tz", account.timeZone)
                        .addValue("created", dialect.instantParam(account.createdAt)).addValue("updated", dialect.instantParam(account.updatedAt))
                        .addValue("lastLogin", dialect.instantParam(account.lastLoginAt)).addValue("suspended", account.suspendedReason)
                        .addValue("deleted", dialect.instantParam(account.deletedAt)).addValue("purge", dialect.instantParam(account.purgeAfter)).addValue("erased", dialect.instantParam(account.erasedAt)),
                )
                account.roles.forEach { jdbc.update("insert into account_roles (account_id, role) values (:a, :r)", mapOf("a" to account.id, "r" to it)) }
                identities.forEach(::insertIdentity)
            }
            true
        } catch (_: DuplicateKeyException) {
            false
        }

    override fun findById(id: String): Account? = one("select * from accounts where id = :v", id)

    override fun findByEmail(email: String): Account? = one("select * from accounts where email = :v", email)

    override fun findByIdentity(method: String, subject: String): Account? =
        jdbc.query(
            "select a.* from accounts a join account_identities i on i.account_id = a.id where i.method = :m and i.subject = :s",
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
        // ERASED 행에는 아무것도 다시 쓰지 않는다 — 지운 뒤 도착한 옛 토큰의 프로필 갱신 · 지우기와 겹친 정지 해제가 개인정보나 상태를 되살리지 못하게
        val n = jdbc.update("update accounts set ${sets.joinToString(", ")} where id = :id and status <> 'ERASED'", p)
        return if (n == 0) null else findById(id)
    }

    override fun markEmailVerified(id: String, now: Instant): Boolean =
        tx.execute {
            val email = jdbc.query("select email from accounts where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.getString("email") }.firstOrNull()
                ?: return@execute false
            jdbc.update(
                "update accounts set email_verified = true, updated_at = :now, " +
                    "status = case when status = 'PENDING_VERIFICATION' then 'ACTIVE' else status end where id = :id",
                MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("id", id),
            )
            jdbc.update("update account_identities set verified = true where account_id = :id and subject = :email", mapOf("id" to id, "email" to email))
            true
        } ?: false

    override fun proveMailbox(id: String, now: Instant, proof: MailboxProof): Boolean =
        tx.execute {
            val row = jdbc.query("select email, email_verified from accounts where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.getString("email") to rs.getBoolean("email_verified") }.firstOrNull()
                ?: return@execute false
            val email = row.first ?: return@execute false
            if (!row.second) {
                // 새 비밀번호를 정하면 옛 비밀번호 수단 행도 지운다(id 가 바뀐다) — 그 행의 id 를 들고 있던 남의 뒤늦은 갱신이 새 비밀번호를 덮지 못하게
                val dropPassword = if (proof.passwordSecret != null) " or method = :pw" else ""
                if (proof.keepIdentityIds.isEmpty()) jdbc.update("delete from account_identities where account_id = :id", mapOf("id" to id))
                else jdbc.update("delete from account_identities where account_id = :id and (id not in (:keep)$dropPassword)", mapOf("id" to id, "keep" to proof.keepIdentityIds, "pw" to SignInMethods.PASSWORD))
            }
            proof.passwordSecret?.let { secret ->
                val updated = jdbc.update(
                    "update account_identities set secret = :s, verified = true where account_id = :id and method = :m and subject = :email",
                    mapOf("s" to secret, "id" to id, "m" to SignInMethods.PASSWORD, "email" to email),
                )
                if (updated == 0) {
                    insertIdentity(Identity(requireNotNull(proof.newPasswordIdentityId) { "newPasswordIdentityId" }, id, SignInMethods.PASSWORD, email, true, secret = secret, createdAt = now))
                }
            }
            jdbc.update(
                "update accounts set email_verified = true, updated_at = :now, " +
                    "status = case when status = 'PENDING_VERIFICATION' then 'ACTIVE' else status end where id = :id",
                MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("id", id),
            )
            jdbc.update("update account_identities set verified = true where account_id = :id and subject = :email", mapOf("id" to id, "email" to email))
            // 증명과 **같은 트랜잭션**에서 이 계정의 열린 코드(이메일 변경 · 다시 인증 · 삭제 확인)와 이 주소의 가입 시도를 닫는다 — 증명 커밋과 정리 사이에 증명 전의 코드가 쓰일 틈이 없다
            jdbc.update(
                "delete from account_challenges where (account_id = :id and purpose in (:purposes)) or (purpose = :signUp and subject = :email)",
                mapOf("id" to id, "email" to email, "signUp" to ChallengePurposes.SIGN_UP, "purposes" to listOf(ChallengePurposes.EMAIL_CHANGE, ChallengePurposes.REAUTH, ChallengePurposes.DELETE_CONFIRM)),
            )
            true
        } ?: false

    override fun changeEmail(id: String, newEmail: String, now: Instant, expectEmailVerified: Boolean?): ChangeEmailResult =
        try {
            tx.execute {
                val row = jdbc.query("select email, email_verified from accounts where id = :id and status <> 'ERASED' for update", mapOf("id" to id)) { rs, _ -> rs.getString("email") to rs.getBoolean("email_verified") }
                if (row.isEmpty()) return@execute ChangeEmailResult.NOT_FOUND
                // 계정 행 락 안에서 — 다시 인증이 본 확인 상태가 그 사이 메일함 증명으로 바뀌었다면 이 변경은 증명 **전에** 시작한 것이다
                if (expectEmailVerified != null && row.single().second != expectEmailVerified) return@execute ChangeEmailResult.STALE
                val old = row.single().first
                jdbc.update(
                    "update accounts set email = :new, email_verified = true, updated_at = :now where id = :id",
                    MapSqlParameterSource().addValue("new", newEmail).addValue("now", dialect.instantParam(now)).addValue("id", id),
                )
                if (old != null) {
                    jdbc.update("update account_identities set subject = :new, verified = true where account_id = :id and subject = :old", mapOf("new" to newEmail, "id" to id, "old" to old))
                }
                ChangeEmailResult.CHANGED
            } ?: ChangeEmailResult.NOT_FOUND
        } catch (_: DuplicateKeyException) {
            ChangeEmailResult.TAKEN
        }

    override fun grantRole(id: String, role: String, now: Instant): Boolean =
        try {
            tx.execute {
                // 계정 행을 잠가 지우기와 줄 세운다 — 지운 뒤에 역할이 뒤늦게 붙지 않는다
                val status = jdbc.query("select status from accounts where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.getString(1) }.firstOrNull()
                if (status == null || status == AccountStatus.ERASED.name) false
                else jdbc.update("insert into account_roles (account_id, role) values (:a, :r)", mapOf("a" to id, "r" to role)) == 1
            } ?: false
        } catch (_: DataIntegrityViolationException) {
            false   // 이미 있다(PK)
        }

    override fun revokeRole(id: String, role: String, now: Instant): Boolean =
        jdbc.update("delete from account_roles where account_id = :a and role = :r", mapOf("a" to id, "r" to role)) == 1

    override fun countActiveWithRole(role: String): Long =
        jdbc.queryForObject(
            "select count(*) from accounts a join account_roles r on r.account_id = a.id where r.role = :role and a.status = 'ACTIVE'",
            mapOf("role" to role), Long::class.java,
        ) ?: 0

    override fun search(email: String?, status: AccountStatus?, page: Int, size: Int): AccountPage {
        val where = mutableListOf<String>()
        val p = MapSqlParameterSource().addValue("limit", size).addValue("offset", page.toLong() * size)
        email?.let { where += "email like :pattern escape '!'"; p.addValue("pattern", "%" + it.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%") }
        if (status == null) where += "status <> 'ERASED'" else { where += "status = :status"; p.addValue("status", status.name) }
        val clause = if (where.isEmpty()) "" else " where " + where.joinToString(" and ")
        val total = jdbc.queryForObject("select count(*) from accounts$clause", p, Long::class.java) ?: 0
        val items = jdbc.query("select * from accounts$clause order by created_at desc, id limit :limit offset :offset", p) { rs, _ -> rs.account(emptySet()) }
        return AccountPage(withRoles(items), total)
    }

    override fun dueForPurge(now: Instant, limit: Int): List<Account> =
        withRoles(
            jdbc.query(
                "select * from accounts where status = 'DELETED' and purge_after is not null and purge_after <= :now order by purge_after limit :limit",
                MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("limit", limit),
            ) { rs, _ -> rs.account(emptySet()) },
        )

    override fun purge(id: String, now: Instant, forced: Boolean): Boolean =
        jdbc.update(
            "delete from accounts where id = :id and " + (if (forced) "status = 'SUSPENDED'" else "status = 'DELETED' and purge_after is not null and purge_after <= :now"),
            MapSqlParameterSource().addValue("id", id).addValue("now", dialect.instantParam(now)),
        ) == 1

    override fun erase(id: String, now: Instant, forced: Boolean): Boolean =
        tx.execute {
            // 계정 행 락 안에서 조건을 다시 본다 — 되살리기(조건부 UPDATE)와 지우기 중 하나만 이긴다
            val row = jdbc.query("select email, status, purge_after from accounts where id = :id for update", mapOf("id" to id)) { rs, _ ->
                Triple(rs.getString("email"), rs.getString("status"), dialect.readInstant(rs, "purge_after"))
            }.firstOrNull() ?: return@execute false
            val (email, status, purgeAfter) = row
            val eligible = if (forced) status == AccountStatus.SUSPENDED.name else status == AccountStatus.DELETED.name && purgeAfter != null && !purgeAfter.isAfter(now)
            if (!eligible) return@execute false
            val p = MapSqlParameterSource().addValue("id", id).addValue("email", email).addValue("now", dialect.instantParam(now))
            jdbc.update("delete from account_roles where account_id = :id", p)
            jdbc.update("delete from account_identities where account_id = :id", p)
            // 토큰은 주인(이메일)이 평문이다 — 계정 id 로 걸린 것과 그 주소로 걸린 것(재설정 · 매직 링크) 모두. 챌린지: 이메일 변경 · 다시 인증 · 삭제 확인 + 같은 주소의 가입 시도(IP 포함)
            jdbc.update("delete from account_tokens where account_id = :id" + if (email != null) " or subject = :email" else "", p)
            jdbc.update("delete from account_challenges where account_id = :id" + if (email != null) " or subject = :email" else "", p)
            jdbc.update("update account_audit set ip = null, detail = null where account_id = :id", p)
            jdbc.update(
                "update accounts set email = null, email_verified = false, status = 'ERASED', display_name = null, locale = null, time_zone = null, " +
                    "suspended_reason = null, last_login_at = null, purge_after = null, erased_at = :now, updated_at = :now where id = :id",
                p,
            )
            true
        } ?: false

    override fun restore(id: String, status: AccountStatus, now: Instant): Boolean =
        jdbc.update(
            "update accounts set status = :status, deleted_at = null, purge_after = null, updated_at = :now " +
                "where id = :id and status = 'DELETED' and purge_after is not null and purge_after > :now",
            MapSqlParameterSource().addValue("id", id).addValue("status", status.name).addValue("now", dialect.instantParam(now)),
        ) == 1

    override fun updateUnlessLast(id: String, patch: AccountPatch, now: Instant, guardRole: String): GuardedResult =
        tx.execute {
            val holders = lockActiveHolders(guardRole)
            if (jdbc.query("select id from accounts where id = :id and status <> 'ERASED'", mapOf("id" to id)) { rs, _ -> rs.getString(1) }.isEmpty()) return@execute GuardedResult.NOT_FOUND
            val leavesActive = patch.status != null && patch.status != AccountStatus.ACTIVE
            if (leavesActive && id in holders && holders.size <= 1) return@execute GuardedResult.LAST
            update(id, patch, now)
            GuardedResult.DONE
        } ?: GuardedResult.NOT_FOUND

    override fun revokeRoleUnlessLast(id: String, role: String, now: Instant): GuardedResult =
        tx.execute {
            val holders = lockActiveHolders(role)
            val has = jdbc.queryForObject("select count(*) from account_roles where account_id = :a and role = :r", mapOf("a" to id, "r" to role), Int::class.java) ?: 0
            if (has == 0) return@execute GuardedResult.NOT_FOUND
            if (id in holders && holders.size <= 1) return@execute GuardedResult.LAST
            jdbc.update("delete from account_roles where account_id = :a and role = :r", mapOf("a" to id, "r" to role))
            GuardedResult.DONE
        } ?: GuardedResult.NOT_FOUND

    /** [role] 의 ACTIVE 보유자 행을 id 순서로 잠근다 — 보호 연산끼리 같은 순서로 잠가 교착 없이 줄 세운다 */
    private fun lockActiveHolders(role: String): Set<String> =
        jdbc.query(
            "select a.id from accounts a join account_roles r on r.account_id = a.id where r.role = :role and a.status = 'ACTIVE' order by a.id for update",
            mapOf("role" to role),
        ) { rs, _ -> rs.getString(1) }.toSet()

    // ---- identities

    override fun addIdentity(identity: Identity): Boolean =
        try {
            tx.execute {
                // 계정 행 락 안에서 — 지우기와 줄 서고, 지운 행에는 수단이 붙지 않는다
                val status = jdbc.query("select status from accounts where id = :id for update", mapOf("id" to identity.accountId)) { rs, _ -> rs.getString(1) }.firstOrNull()
                if (status == null || status == AccountStatus.ERASED.name) false else { insertIdentity(identity); true }
            } ?: false
        } catch (_: DataIntegrityViolationException) { false }

    override fun addIdentityIfEmailVerified(identity: Identity, expectEmailVerified: Boolean): AddIdentityResult =
        try {
            tx.execute {
                // 계정 행 락을 먼저 — 메일함 증명 트랜잭션(같은 행 락)이 끝날 때까지 기다린 뒤 증명 뒤의 상태를 본다
                val verified = jdbc.query("select email_verified from accounts where id = :id and status <> 'ERASED' for update", mapOf("id" to identity.accountId)) { rs, _ -> rs.getBoolean(1) }.firstOrNull()
                if (verified != expectEmailVerified) AddIdentityResult.STALE else { insertIdentity(identity); AddIdentityResult.ADDED }
            } ?: AddIdentityResult.STALE
        } catch (_: DataIntegrityViolationException) {
            AddIdentityResult.DUPLICATE
        }

    private fun insertIdentity(i: Identity) {
        jdbc.update(
            """
            insert into account_identities (id, account_id, method, subject, verified, secret, metadata, created_at, last_used_at)
            values (:id, :account, :method, :subject, :verified, :secret, :metadata, :created, :lastUsed)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", i.id).addValue("account", i.accountId).addValue("method", i.method).addValue("subject", i.subject)
                .addValue("verified", i.verified).addValue("secret", i.secret).addValue("metadata", i.metadata)
                .addValue("created", dialect.instantParam(i.createdAt)).addValue("lastUsed", dialect.instantParam(i.lastUsedAt)),
        )
    }

    override fun findIdentity(method: String, subject: String): Identity? =
        jdbc.query("select * from account_identities where method = :m and subject = :s", mapOf("m" to method, "s" to subject)) { rs, _ -> rs.identity() }.firstOrNull()

    override fun findIdentityById(id: String): Identity? =
        jdbc.query("select * from account_identities where id = :id", mapOf("id" to id)) { rs, _ -> rs.identity() }.firstOrNull()

    override fun identitiesOf(accountId: String): List<Identity> =
        jdbc.query("select * from account_identities where account_id = :a order by created_at, id", mapOf("a" to accountId)) { rs, _ -> rs.identity() }

    override fun removeIdentity(accountId: String, identityId: String): Boolean =
        jdbc.update("delete from account_identities where id = :id and account_id = :a", mapOf("id" to identityId, "a" to accountId)) == 1

    override fun removeIdentityUnlessLast(accountId: String, identityId: String, credentialMethods: Collection<String>): RemoveIdentityResult =
        tx.execute {
            // 계정 행을 잠가 같은 계정의 동시 해제를 줄 세운다 — 둘 다 "다른 게 남아 있다" 고 보고 둘 다 지우는 일이 없다
            if (jdbc.query("select id from accounts where id = :a for update", mapOf("a" to accountId)) { rs, _ -> rs.getString(1) }.isEmpty()) return@execute RemoveIdentityResult.NOT_FOUND
            val method = jdbc.query("select method from account_identities where id = :id and account_id = :a", mapOf("id" to identityId, "a" to accountId)) { rs, _ -> rs.getString(1) }.firstOrNull()
                ?: return@execute RemoveIdentityResult.NOT_FOUND
            val others = if (credentialMethods.isEmpty()) 0 else jdbc.queryForObject(
                "select count(*) from account_identities where account_id = :a and id <> :id and method in (:methods)",
                mapOf("a" to accountId, "id" to identityId, "methods" to credentialMethods), Int::class.java,
            ) ?: 0
            if (method in credentialMethods && others == 0) return@execute RemoveIdentityResult.LAST
            jdbc.update("delete from account_identities where id = :id", mapOf("id" to identityId))
            RemoveIdentityResult.REMOVED
        } ?: RemoveIdentityResult.NOT_FOUND

    override fun updateIdentitySecret(identityId: String, secret: String?, expectedSecret: String?): Boolean =
        jdbc.update(
            "update account_identities set secret = :s where id = :id" + (if (expectedSecret != null) " and secret = :old" else ""),
            MapSqlParameterSource().addValue("s", secret).addValue("id", identityId).addValue("old", expectedSecret),
        ) == 1

    override fun touchIdentity(identityId: String, now: Instant) {
        jdbc.update("update account_identities set last_used_at = :now where id = :id", MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("id", identityId))
    }

    // ---- mapping

    private fun withRoles(accounts: List<Account>): List<Account> {
        if (accounts.isEmpty()) return accounts
        val roles = HashMap<String, MutableSet<String>>()
        jdbc.query("select account_id, role from account_roles where account_id in (:ids)", mapOf("ids" to accounts.map { it.id })) { rs ->
            roles.getOrPut(rs.getString("account_id")) { linkedSetOf() }.add(rs.getString("role"))
        }
        return accounts.map { it.copy(roles = roles[it.id] ?: emptySet()) }
    }

    private fun ResultSet.account(roles: Set<String>) = Account(
        id = getString("id"), email = getString("email"), emailVerified = getBoolean("email_verified"), status = AccountStatus.valueOf(getString("status")), roles = roles,
        displayName = getString("display_name"), locale = getString("locale"), timeZone = getString("time_zone"),
        createdAt = dialect.readInstant(this, "created_at")!!, updatedAt = dialect.readInstant(this, "updated_at")!!, lastLoginAt = dialect.readInstant(this, "last_login_at"),
        suspendedReason = getString("suspended_reason"), deletedAt = dialect.readInstant(this, "deleted_at"), purgeAfter = dialect.readInstant(this, "purge_after"),
        erasedAt = dialect.readInstant(this, "erased_at"),
    )

    private fun ResultSet.identity() = Identity(
        id = getString("id"), accountId = getString("account_id"), method = getString("method"), subject = getString("subject"), verified = getBoolean("verified"),
        secret = getString("secret"), metadata = getString("metadata"), createdAt = dialect.readInstant(this, "created_at")!!, lastUsedAt = dialect.readInstant(this, "last_used_at"),
    )
}
