package dev.sumin.skeleton.authorprofile.jdbc

import dev.sumin.skeleton.authorprofile.AuthorProfile
import dev.sumin.skeleton.authorprofile.AuthorProfileRepository
import dev.sumin.skeleton.authorprofile.AuthorProfileStatus
import dev.sumin.skeleton.authorprofile.CapInsert
import dev.sumin.skeleton.authorprofile.RenameResult
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/*
 * 규칙 (포트 KDoc `AuthorProfileRepository` 가 정본):
 *  - 유니크는 DB 가 가른다. 넣기는 충돌하면 아무것도 하지 않는 insert 이고(PostgreSQL `on conflict do nothing` — 대상 없이: id · primary_of · (name_key, tag) 어느 쪽이든,
 *    MySQL `SqlDialect.insertIgnore` = `on duplicate key update`), **넣었는지는 갱신 행 수가 아니라 id 로 다시 읽어** 판정한다 (MySQL 은 이미 있는 행에도 1 을 돌려준다 — SqlDialect 문서).
 *    예외를 잡아 삼키는 방식은 PostgreSQL 에서 트랜잭션 전체를 abort 시키므로 넣기에는 쓰지 않는다.
 *  - 상한은 그 계정의 첫 프로필 줄을 `for update` 로 잠근 트랜잭션 안에서 센다 — 같은 계정의 동시 요청은 이 잠금에서 줄을 선다. 잠그는 줄은 계정당 하나뿐이라 교착이 없다.
 *  - 쓰기는 주인 계정과 상태를 조건에 건다 — 남의 · 없는 · 지워진 프로필은 구별하지 않는다.
 *  - 계정 id 와 프로필 id 의 쌍은 로그에 남기지 않는다 (예외 메시지에도 싣지 않는다).
 */
class JdbcAuthorProfileRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
) : AuthorProfileRepository {
    override fun find(id: String): AuthorProfile? =
        jdbc.query("$SELECT where id = :id", mapOf("id" to id)) { rs, _ -> rs.profile() }.firstOrNull()

    override fun findAll(ids: Collection<String>): Map<String, AuthorProfile> {
        if (ids.isEmpty()) return emptyMap()
        val found = LinkedHashMap<String, AuthorProfile>()
        ids.toSet().chunked(CHUNK).forEach { chunk ->
            jdbc.query("$SELECT where id in (:ids)", mapOf("ids" to chunk)) { rs, _ -> rs.profile() }.forEach { found[it.id] = it }
        }
        return found
    }

    override fun findActive(accountId: String): List<AuthorProfile> =
        jdbc.query("$SELECT where account_id = :account and status = 'ACTIVE' order by created_at, id", mapOf("account" to accountId)) { rs, _ -> rs.profile() }

    override fun countActive(accountId: String): Int = countActiveOf(accountId)

    override fun findPrimary(accountId: String): AuthorProfile? =
        jdbc.query("$SELECT where primary_of = :account", mapOf("account" to accountId)) { rs, _ -> rs.profile() }.firstOrNull()

    override fun tagsOf(key: String): Set<String> =
        jdbc.queryForList("select tag from author_profiles where name_key = :key and tag is not null and status = 'ACTIVE'", mapOf("key" to key), String::class.java)
            .filterNotNull().toSet()

    override fun primariesByKey(key: String): List<AuthorProfile> =
        jdbc.query("$SELECT where name_key = :key and primary_of is not null and status = 'ACTIVE' order by created_at, id", mapOf("key" to key)) { rs, _ -> rs.profile() }

    override fun countCreatedSince(accountId: String, since: Instant): Int =
        jdbc.queryForObject(
            "select count(*) from author_profiles where account_id = :account and primary_of is null and created_at >= :since",
            MapSqlParameterSource().addValue("account", accountId).addValue("since", dialect.instantParam(since)),
            Int::class.java,
        ) ?: 0

    override fun insert(profile: AuthorProfile): Boolean {
        require(profile.primaryOf != null) { "insert is for the first profile (primary_of is set); use insertWithinCap" }
        return insertIgnoring(profile)
    }

    override fun insertWithinCap(profile: AuthorProfile, maxActive: Int): CapInsert {
        require(profile.primaryOf == null) { "insertWithinCap is for user-made profiles (primary_of is null); use insert" }
        return tx.execute {
            // 그 계정의 첫 프로필 줄(지워졌어도)을 잠근다 — 같은 계정의 동시 요청이 여기서 줄을 선다. 센 값은 잠금을 잡은 뒤의 것이어야 한다
            val locked = jdbc.queryForList("select id from author_profiles where primary_of = :account for update", mapOf("account" to profile.accountId), String::class.java)
            check(locked.isNotEmpty()) { "the account has no first profile row to lock - ensure it before insertWithinCap" }
            when {
                countActiveOf(profile.accountId) >= maxActive -> CapInsert.LIMIT_REACHED
                insertIgnoring(profile) -> CapInsert.INSERTED
                else -> CapInsert.NAME_TAKEN
            }
        }
    }

    override fun rename(id: String, accountId: String, name: String, key: String, tag: String?, now: Instant): RenameResult {
        val changed = try {
            jdbc.update(
                "update author_profiles set name = :name, name_key = :key, tag = :tag, updated_at = :now where id = :id and account_id = :account and status = 'ACTIVE'",
                MapSqlParameterSource().addValue("name", name).addValue("key", key).addValue("tag", tag).addValue("now", dialect.instantParam(now))
                    .addValue("id", id).addValue("account", accountId),
            )
        } catch (e: DuplicateKeyException) {
            // (name_key, tag) 유니크가 가른다. 단일 문장이라 PostgreSQL 도 이 문장만 실패한다 (바깥 트랜잭션 없이 부른다)
            return RenameResult.TAKEN
        }
        if (changed > 0) return RenameResult.DONE
        // MySQL 은 값이 하나도 안 바뀌면 0 을 돌려준다 — 있는 줄인지 다시 본다
        return if (ownLiving(id, accountId)) RenameResult.DONE else RenameResult.NOT_FOUND
    }

    override fun markDeleted(id: String, accountId: String, now: Instant): Boolean =
        jdbc.update(
            "update author_profiles set status = 'DELETED', name = null, name_key = null, tag = null, deleted_at = :now, updated_at = :now " +
                "where id = :id and account_id = :account and status = 'ACTIVE'",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("id", id).addValue("account", accountId),
        ) > 0

    override fun markAdopted(accountId: String, now: Instant): Boolean =
        jdbc.update(
            "update author_profiles set adopted_at = :now where primary_of = :account and adopted_at is null",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("account", accountId),
        ) > 0

    override fun deleteByAccount(accountId: String): Int =
        jdbc.update("delete from author_profiles where account_id = :account", mapOf("account" to accountId))

    // ---- 내부 ----

    private fun countActiveOf(accountId: String): Int =
        jdbc.queryForObject("select count(*) from author_profiles where account_id = :account and status = 'ACTIVE'", mapOf("account" to accountId), Int::class.java) ?: 0

    private fun ownLiving(id: String, accountId: String): Boolean =
        (jdbc.queryForObject(
            "select count(*) from author_profiles where id = :id and account_id = :account and status = 'ACTIVE'",
            mapOf("id" to id, "account" to accountId), Int::class.java,
        ) ?: 0) > 0

    /** 충돌하면 아무것도 하지 않고 false. 바깥 트랜잭션 안에서 불러도 PostgreSQL 트랜잭션이 망가지지 않는다 */
    private fun insertIgnoring(profile: AuthorProfile): Boolean {
        jdbc.update(
            insertSql,
            MapSqlParameterSource()
                .addValue("id", profile.id).addValue("account_id", profile.accountId).addValue("primary_of", profile.primaryOf)
                .addValue("name", profile.name).addValue("name_key", profile.nameKey).addValue("tag", profile.tag)
                .addValue("status", profile.status.name).addValue("adopted_at", dialect.instantParam(profile.adoptedAt))
                .addValue("created_at", dialect.instantParam(profile.createdAt)).addValue("updated_at", dialect.instantParam(profile.updatedAt))
                .addValue("deleted_at", dialect.instantParam(profile.deletedAt)),
        )
        // 갱신 행 수로는 못 가른다 (MySQL) — 방금 넣은 id 가 있으면 우리가 넣은 것이다 (id 는 무작위 128비트)
        return (jdbc.queryForObject("select count(*) from author_profiles where id = :id", mapOf("id" to profile.id), Int::class.java) ?: 0) > 0
    }

    private val insertSql: String = if (dialect.vendor == "postgresql") {
        // 충돌 대상을 적지 않는다: primary_of · (name_key, tag) 어느 유니크가 겹쳐도 같은 길이다 (SqlDialect.insertIgnore 는 대상 하나만 받는다)
        "insert into author_profiles ($COLUMNS) values (${COLUMNS.split(", ").joinToString { ":$it" }}) on conflict do nothing"
    } else {
        // MySQL: `on duplicate key update id = id` 가 어느 유니크 키가 겹쳐도 아무것도 하지 않는다 (insert ignore 는 잘림 같은 다른 오류까지 삼킨다)
        dialect.insertIgnore("author_profiles", COLUMNS.split(", "), listOf("id"))
    }

    private fun ResultSet.profile() = AuthorProfile(
        id = getString("id"),
        accountId = getString("account_id"),
        primaryOf = getString("primary_of"),
        name = getString("name"),
        nameKey = getString("name_key"),
        tag = getString("tag"),
        status = AuthorProfileStatus.valueOf(getString("status")),
        adoptedAt = dialect.readInstant(this, "adopted_at"),
        createdAt = dialect.readInstant(this, "created_at")!!,
        updatedAt = dialect.readInstant(this, "updated_at")!!,
        deletedAt = dialect.readInstant(this, "deleted_at"),
    )

    private companion object {
        const val COLUMNS = "id, account_id, primary_of, name, name_key, tag, status, adopted_at, created_at, updated_at, deleted_at"
        const val SELECT = "select $COLUMNS from author_profiles"

        /** `in (…)` 한 번에 넣는 id 수 — 두 DB 의 바인드 변수 한도보다 훨씬 작다 */
        const val CHUNK = 500
    }
}
