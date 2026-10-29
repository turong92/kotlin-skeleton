package dev.sumin.skeleton.badge.jdbc

import dev.sumin.skeleton.badge.BadgeAction
import dev.sumin.skeleton.badge.BadgeAward
import dev.sumin.skeleton.badge.BadgeAwardEvent
import dev.sumin.skeleton.badge.BadgeAwardStatus
import dev.sumin.skeleton.badge.BadgeDefinition
import dev.sumin.skeleton.badge.BadgeSource
import dev.sumin.skeleton.badge.BadgeStatus
import dev.sumin.skeleton.badge.BadgeStore
import dev.sumin.skeleton.badge.PublicBadge
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 배지 표 셋(`badge_definitions` · `badge_awards` · `badge_award_events`). 상태 전이는 `where status = …` 조건부 갱신이라 동시에 와도 한쪽만 이긴다.
 * 넣기는 [SqlDialect.insertIgnore](PG 에서 유니크 위반 예외는 호출자 트랜잭션 전체를 abort 시킨다)이고 **넣었는지는 내가 만든 id 가 있는지로 본다**
 * (MySQL 의 `on duplicate key update` 는 이미 있는 행에도 1 을 돌려준다). 잠금 읽기(`for update`)는 두 DB 모두 현재 값을 읽는다.
 * 공개 프로필 질의([publicBadgesOf])는 `status = 'ACTIVE' and visible` 인 줄만 읽는다 - 걸러 내는 것이 아니라 읽지 않는다.
 */
class JdbcBadgeStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
) : BadgeStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }

    // 정의

    override fun insertDefinition(definition: BadgeDefinition): Boolean {
        jdbc.update(
            dialect.insertIgnore("badge_definitions", DEFINITION_COLUMNS, listOf("code")),
            MapSqlParameterSource()
                .addValue("id", definition.id)
                .addValue("code", definition.code)
                .addValue("name", definition.name)
                .addValue("description", definition.description)
                .addValue("image_url", definition.imageUrl, Types.VARCHAR)
                .addValue("hidden", definition.hidden)
                .addValue("sort_order", definition.sortOrder)
                .addValue("status", definition.status.name)
                .addValue("created_at", instant(definition.createdAt))
                .addValue("updated_at", instant(definition.updatedAt)),
        )
        return jdbc.queryForObject("select count(*) from badge_definitions where id = :id", mapOf("id" to definition.id), Long::class.java) == 1L
    }

    override fun findDefinition(id: String): BadgeDefinition? =
        jdbc.query("select * from badge_definitions where id = :id", mapOf("id" to id)) { rs, _ -> rs.toDefinition() }.firstOrNull()

    override fun findDefinitionByCode(code: String): BadgeDefinition? =
        jdbc.query("select * from badge_definitions where code = :code", mapOf("code" to code)) { rs, _ -> rs.toDefinition() }.firstOrNull()

    override fun findDefinitions(ids: Collection<String>): Map<String, BadgeDefinition> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query("select * from badge_definitions where id in (:ids)", mapOf("ids" to ids.toList())) { rs, _ -> rs.toDefinition() }.associateBy { it.id }
    }

    override fun listDefinitions(): List<BadgeDefinition> =
        jdbc.query("select * from badge_definitions order by sort_order, id") { rs, _ -> rs.toDefinition() }

    override fun holderCounts(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        jdbc.query("select badge_id, count(*) as n from badge_awards where status = 'ACTIVE' group by badge_id") { rs -> counts[rs.getString("badge_id")] = rs.getInt("n") }
        return counts
    }

    override fun updateDefinition(definition: BadgeDefinition): Boolean =
        jdbc.update(
            "update badge_definitions set name = :name, description = :description, image_url = :image_url, hidden = :hidden, sort_order = :sort_order, updated_at = :at where id = :id",
            MapSqlParameterSource()
                .addValue("id", definition.id)
                .addValue("name", definition.name)
                .addValue("description", definition.description)
                .addValue("image_url", definition.imageUrl, Types.VARCHAR)
                .addValue("hidden", definition.hidden)
                .addValue("sort_order", definition.sortOrder)
                .addValue("at", instant(definition.updatedAt)),
        ) == 1

    override fun updateDefinitionStatus(id: String, status: BadgeStatus, at: Instant): Boolean =
        jdbc.update("update badge_definitions set status = :status, updated_at = :at where id = :id", mapOf("id" to id, "status" to status.name, "at" to instant(at))) == 1

    // 보유

    override fun lockAward(badgeId: String, accountId: String): BadgeAward? =
        jdbc.query("select * from badge_awards where badge_id = :b and account_id = :a for update", mapOf("b" to badgeId, "a" to accountId)) { rs, _ -> rs.toAward() }.firstOrNull()

    override fun lockAwardById(id: String): BadgeAward? =
        jdbc.query("select * from badge_awards where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.toAward() }.firstOrNull()

    override fun insertAwardIfAbsent(award: BadgeAward): Boolean {
        jdbc.update(
            dialect.insertIgnore("badge_awards", AWARD_COLUMNS, listOf("badge_id", "account_id")),
            MapSqlParameterSource()
                .addValue("id", award.id)
                .addValue("badge_id", award.badgeId)
                .addValue("account_id", award.accountId)
                .addValue("status", award.status.name)
                .addValue("visible", award.visible)
                .addValue("awarded_at", instant(award.awardedAt))
                .addValue("revoked_at", instant(award.revokedAt)),
        )
        return jdbc.queryForObject("select count(*) from badge_awards where id = :id", mapOf("id" to award.id), Long::class.java) == 1L
    }

    override fun reactivateAward(id: String, at: Instant): Boolean =
        jdbc.update("update badge_awards set status = 'ACTIVE', awarded_at = :at, revoked_at = null where id = :id and status = 'REVOKED'", mapOf("id" to id, "at" to instant(at))) == 1

    override fun revokeAward(id: String, at: Instant): Boolean =
        jdbc.update("update badge_awards set status = 'REVOKED', revoked_at = :at where id = :id and status = 'ACTIVE'", mapOf("id" to id, "at" to instant(at))) == 1

    override fun setVisible(id: String, accountId: String, visible: Boolean): Boolean =
        jdbc.update("update badge_awards set visible = :v where id = :id and account_id = :a and status = 'ACTIVE'", mapOf("id" to id, "a" to accountId, "v" to visible)) == 1

    override fun activeAwardsOf(accountId: String): List<BadgeAward> =
        jdbc.query(
            "select a.* from badge_awards a join badge_definitions d on d.id = a.badge_id where a.account_id = :a and a.status = 'ACTIVE' order by d.sort_order, d.id",
            mapOf("a" to accountId),
        ) { rs, _ -> rs.toAward() }

    override fun publicBadgesOf(accountIds: Collection<String>): Map<String, List<PublicBadge>> {
        if (accountIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, MutableList<PublicBadge>>()
        // 읽는 열도 공개 모양 넷(+ 묶는 계정 id)뿐이다 - 줄 id · 시각 · 상태는 가져오지 않는다
        jdbc.query(
            "select a.account_id, d.code, d.name, d.description, d.image_url from badge_awards a join badge_definitions d on d.id = a.badge_id " +
                "where a.account_id in (:ids) and a.status = 'ACTIVE' and a.visible = :visible order by d.sort_order, d.id",
            mapOf("ids" to accountIds.toList(), "visible" to true),
        ) { rs ->
            result.getOrPut(rs.getString("account_id")) { ArrayList() } += PublicBadge(rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getString("image_url"))
        }
        return result
    }

    // 내역

    override fun hasEvent(action: BadgeAction, source: BadgeSource, sourceRef: String): Boolean =
        jdbc.queryForObject(
            "select count(*) from badge_award_events where action = :action and source_type = :source and source_ref = :ref",
            mapOf("action" to action.name, "source" to source.name, "ref" to sourceRef),
            Long::class.java,
        )!! > 0L

    override fun insertEventIfAbsent(event: BadgeAwardEvent): Boolean {
        jdbc.update(
            dialect.insertIgnore("badge_award_events", EVENT_COLUMNS, listOf("action", "source_type", "source_ref")),
            MapSqlParameterSource()
                .addValue("id", event.id)
                .addValue("award_id", event.awardId)
                .addValue("badge_id", event.badgeId)
                .addValue("account_id", event.accountId)
                .addValue("action", event.action.name)
                .addValue("source_type", event.source.name)
                .addValue("source_ref", event.sourceRef, Types.VARCHAR)
                .addValue("actor_id", event.actorId, Types.VARCHAR)
                .addValue("reason", event.reason, Types.VARCHAR)
                .addValue("created_at", instant(event.createdAt)),
        )
        return jdbc.queryForObject("select count(*) from badge_award_events where id = :id", mapOf("id" to event.id), Long::class.java) == 1L
    }

    override fun pageEvents(badgeId: String?, accountId: String?, before: String?, limit: Int): List<BadgeAwardEvent> =
        jdbc.query(
            "select * from badge_award_events where (:badge is null or badge_id = :badge) and (:account is null or account_id = :account) and (:before is null or id < :before) order by id desc limit :limit",
            MapSqlParameterSource().addValue("badge", badgeId, Types.VARCHAR).addValue("account", accountId, Types.VARCHAR).addValue("before", before, Types.VARCHAR).addValue("limit", limit),
        ) { rs, _ -> rs.toEvent() }

    // 탈퇴

    override fun eraseAccount(accountId: String): Int {
        val events = jdbc.update("delete from badge_award_events where account_id = :a", mapOf("a" to accountId))
        val awards = jdbc.update("delete from badge_awards where account_id = :a", mapOf("a" to accountId))
        return events + awards
    }

    // 읽기

    private fun instant(value: Instant?): Any? = dialect.instantParam(value)

    private fun ResultSet.toDefinition() = BadgeDefinition(
        id = getString("id"),
        code = getString("code"),
        name = getString("name"),
        description = getString("description"),
        imageUrl = getString("image_url"),
        hidden = getBoolean("hidden"),
        sortOrder = getInt("sort_order"),
        status = BadgeStatus.valueOf(getString("status")),
        createdAt = dialect.readInstant(this, "created_at")!!,
        updatedAt = dialect.readInstant(this, "updated_at")!!,
    )

    private fun ResultSet.toAward() = BadgeAward(
        id = getString("id"),
        badgeId = getString("badge_id"),
        accountId = getString("account_id"),
        status = BadgeAwardStatus.valueOf(getString("status")),
        visible = getBoolean("visible"),
        awardedAt = dialect.readInstant(this, "awarded_at")!!,
        revokedAt = dialect.readInstant(this, "revoked_at"),
    )

    private fun ResultSet.toEvent() = BadgeAwardEvent(
        id = getString("id"),
        awardId = getString("award_id"),
        badgeId = getString("badge_id"),
        accountId = getString("account_id"),
        action = BadgeAction.valueOf(getString("action")),
        source = BadgeSource.valueOf(getString("source_type")),
        sourceRef = getString("source_ref"),
        actorId = getString("actor_id"),
        reason = getString("reason"),
        createdAt = dialect.readInstant(this, "created_at")!!,
    )

    private companion object {
        val DEFINITION_COLUMNS = listOf("id", "code", "name", "description", "image_url", "hidden", "sort_order", "status", "created_at", "updated_at")
        val AWARD_COLUMNS = listOf("id", "badge_id", "account_id", "status", "visible", "awarded_at", "revoked_at")
        val EVENT_COLUMNS = listOf("id", "award_id", "badge_id", "account_id", "action", "source_type", "source_ref", "actor_id", "reason", "created_at")
    }
}
