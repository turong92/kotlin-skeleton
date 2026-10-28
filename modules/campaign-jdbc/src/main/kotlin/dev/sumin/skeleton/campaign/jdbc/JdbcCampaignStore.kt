package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.campaign.Campaign
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.Referral
import dev.sumin.skeleton.campaign.ReferralStatus
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 이벤트 네 표. 「새로 넣었나」를 갱신 행 수로 판정해야 하는 곳(출석 · 초대 관계 · 코드)은 [insertIgnoreSql] 을 쓴다 -
 * PostgreSQL `on conflict do nothing` / MySQL `insert ignore` 모두 이미 있으면 0 줄을 돌려준다(공용 `SqlDialect.insertIgnore` 의 MySQL 판은 이미 있는 행에도 1 을 돌려줘 쓸 수 없다).
 * 유니크 위반 예외는 PostgreSQL 에서 호출자 트랜잭션을 통째로 abort 시키므로 쓰지 않는다. 상태 전이는 `where status = …` 조건부 갱신이다.
 */
class JdbcCampaignStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val json: JsonCodec,
) : CampaignStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }

    override fun insert(campaign: Campaign) {
        jdbc.update(
            "insert into campaigns (id, kind, name, enabled, starts_at, ends_at, config_json, created_at, updated_at) values (:id, :kind, :name, :enabled, :starts, :ends, :config, :created, :updated)",
            params(campaign).addValue("kind", campaign.kind).addValue("created", dialect.instantParam(campaign.createdAt)),
        )
    }

    override fun update(campaign: Campaign): Boolean =
        jdbc.update("update campaigns set name = :name, starts_at = :starts, ends_at = :ends, config_json = :config, updated_at = :updated where id = :id", params(campaign)) == 1

    override fun setEnabled(id: String, enabled: Boolean, at: Instant): Boolean =
        jdbc.update("update campaigns set enabled = :enabled, updated_at = :at where id = :id", mapOf("id" to id, "enabled" to enabled, "at" to dialect.instantParam(at))) == 1

    override fun find(id: String): Campaign? = jdbc.query("select * from campaigns where id = :id", mapOf("id" to id)) { rs, _ -> rs.toCampaign() }.firstOrNull()

    override fun list(): List<Campaign> = jdbc.query("select * from campaigns order by id desc") { rs, _ -> rs.toCampaign() }

    override fun insertAttendance(campaignId: String, accountId: String, day: LocalDate, at: Instant): Boolean =
        jdbc.update(
            insertIgnoreSql("campaign_attendance", listOf("campaign_id", "account_id", "day", "created_at")),
            mapOf("campaign_id" to campaignId, "account_id" to accountId, "day" to day, "created_at" to dialect.instantParam(at)),
        ) == 1

    override fun attendanceDays(campaignId: String, accountId: String): List<LocalDate> =
        jdbc.query(
            "select day from campaign_attendance where campaign_id = :c and account_id = :a order by day",
            mapOf("c" to campaignId, "a" to accountId),
        ) { rs, _ -> rs.getObject("day", LocalDate::class.java) }

    override fun referralCode(accountId: String, newCode: () -> String): String {
        repeat(MAX_CODE_TRIES) {
            existingCode(accountId)?.let { return it }
            jdbc.update(
                insertIgnoreSql("campaign_referral_codes", listOf("account_id", "code", "created_at")),
                mapOf("account_id" to accountId, "code" to newCode(), "created_at" to dialect.instantParam(Instant.now())),
            )
        }
        return existingCode(accountId) ?: error("could not allocate a referral code")   // 코드가 겹치면 다시 뽑는다
    }

    private fun existingCode(accountId: String): String? =
        jdbc.queryForList("select code from campaign_referral_codes where account_id = :a", mapOf("a" to accountId), String::class.java).firstOrNull()

    override fun accountOfCode(code: String): String? =
        jdbc.queryForList("select account_id from campaign_referral_codes where code = :c", mapOf("c" to code), String::class.java).firstOrNull()

    override fun insertReferral(referral: Referral): Boolean =
        jdbc.update(
            insertIgnoreSql("campaign_referrals", listOf("invitee_id", "inviter_id", "campaign_id", "status", "claimed_at", "qualified_at")),
            MapSqlParameterSource()
                .addValue("invitee_id", referral.inviteeId).addValue("inviter_id", referral.inviterId).addValue("campaign_id", referral.campaignId)
                .addValue("status", referral.status.name).addValue("claimed_at", dialect.instantParam(referral.claimedAt))
                .addValue("qualified_at", dialect.instantParam(referral.qualifiedAt)),
        ) == 1

    override fun referralOfInvitee(inviteeId: String): Referral? =
        jdbc.query("select * from campaign_referrals where invitee_id = :i", mapOf("i" to inviteeId)) { rs, _ ->
            Referral(
                rs.getString("invitee_id"), rs.getString("inviter_id"), rs.getString("campaign_id"), ReferralStatus.valueOf(rs.getString("status")),
                dialect.readInstant(rs, "claimed_at")!!, dialect.readInstant(rs, "qualified_at"),
            )
        }.firstOrNull()

    override fun countReferrals(inviterId: String, campaignId: String?, status: ReferralStatus?): Long =
        jdbc.queryForObject(
            "select count(*) from campaign_referrals where inviter_id = :inviter and (:campaign is null or campaign_id = :campaign) and (:status is null or status = :status)",
            MapSqlParameterSource().addValue("inviter", inviterId).addValue("campaign", campaignId, java.sql.Types.VARCHAR).addValue("status", status?.name, java.sql.Types.VARCHAR),
            Long::class.java,
        ) ?: 0L

    override fun settleReferral(inviteeId: String, to: ReferralStatus, at: Instant): Boolean =
        jdbc.update(
            "update campaign_referrals set status = :to, qualified_at = :at where invitee_id = :i and status = 'CLAIMED'",
            mapOf("i" to inviteeId, "to" to to.name, "at" to dialect.instantParam(at)),
        ) == 1

    override fun deleteByAccount(accountId: String) {
        val p = mapOf("a" to accountId)
        jdbc.update("delete from campaign_attendance where account_id = :a", p)
        jdbc.update("delete from campaign_referral_codes where account_id = :a", p)
        jdbc.update("delete from campaign_referrals where invitee_id = :a or inviter_id = :a", p)
    }

    private fun insertIgnoreSql(table: String, columns: List<String>): String {
        val names = columns.joinToString(", ")
        val values = columns.joinToString(", ") { ":$it" }
        return if (dialect.vendor == "mysql") "insert ignore into $table ($names) values ($values)" else "insert into $table ($names) values ($values) on conflict do nothing"
    }

    private fun params(c: Campaign) = MapSqlParameterSource()
        .addValue("id", c.id).addValue("name", c.name).addValue("enabled", c.enabled)
        .addValue("starts", dialect.instantParam(c.startsAt)).addValue("ends", dialect.instantParam(c.endsAt))
        .addValue("config", json.canonicalString(json.toDocument(c.config))).addValue("updated", dialect.instantParam(c.updatedAt))

    @Suppress("UNCHECKED_CAST")
    private fun ResultSet.toCampaign() = Campaign(
        id = getString("id"), kind = getString("kind"), name = getString("name"), enabled = getBoolean("enabled"),
        startsAt = dialect.readInstant(this, "starts_at")!!, endsAt = dialect.readInstant(this, "ends_at"),
        config = json.fromDocument(json.parse(getString("config_json")), Map::class) as Map<String, Any?>,
        createdAt = dialect.readInstant(this, "created_at")!!, updatedAt = dialect.readInstant(this, "updated_at")!!,
    )

    private companion object {
        const val MAX_CODE_TRIES = 5
    }
}
