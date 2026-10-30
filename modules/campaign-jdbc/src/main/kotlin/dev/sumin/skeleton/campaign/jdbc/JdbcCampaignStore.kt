package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.campaign.Campaign
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.Referral
import dev.sumin.skeleton.campaign.ReferralFilter
import dev.sumin.skeleton.campaign.ReferralFlag
import dev.sumin.skeleton.campaign.ReferralPosition
import dev.sumin.skeleton.campaign.ReferralStatus
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.sql.Types
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
    private val time: TimeProvider,
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

    override fun upsertProgressMax(accountId: String, metric: String, value: Long, at: Instant): Long {
        // 한 문장의 max 갱신 - 동시에 와도 큰 쪽이 남고, 행 잠금이 커밋까지 이어져 같은 수치의 보고가 줄을 선다
        val sql = if (dialect.vendor == "mysql") {
            "insert into campaign_progress (account_id, metric, value, updated_at) values (:a, :m, :v, :at) on duplicate key update value = greatest(value, :v), updated_at = :at"
        } else {
            "insert into campaign_progress (account_id, metric, value, updated_at) values (:a, :m, :v, :at) on conflict (account_id, metric) do update set value = greatest(campaign_progress.value, :v), updated_at = :at"
        }
        jdbc.update(sql, mapOf("a" to accountId, "m" to metric, "v" to value, "at" to dialect.instantParam(at)))
        return progress(accountId, metric) ?: value
    }

    override fun progress(accountId: String, metric: String): Long? =
        jdbc.query("select value from campaign_progress where account_id = :a and metric = :m", mapOf("a" to accountId, "m" to metric)) { rs, _ -> rs.getLong("value") }.firstOrNull()

    override fun referralCode(accountId: String, newCode: () -> String): String {
        repeat(MAX_CODE_TRIES) {
            existingCode(accountId)?.let { return it }
            jdbc.update(
                insertIgnoreSql("campaign_referral_codes", listOf("account_id", "code", "created_at")),
                mapOf("account_id" to accountId, "code" to newCode(), "created_at" to dialect.instantParam(time.now())),
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
            insertIgnoreSql(
                "campaign_referrals",
                listOf(
                    "invitee_id", "inviter_id", "campaign_id", "status", "claimed_at", "qualified_at",
                    "qualify_event", "release_at", "settled_at", "settled_by", "cancel_reason", "flags", "claim_network",
                ),
            ),
            MapSqlParameterSource()
                .addValue("invitee_id", referral.inviteeId).addValue("inviter_id", referral.inviterId).addValue("campaign_id", referral.campaignId)
                .addValue("status", referral.status.name).addValue("claimed_at", dialect.instantParam(referral.claimedAt))
                .addValue("qualified_at", dialect.instantParam(referral.qualifiedAt))
                .addValue("qualify_event", referral.qualifyEvent, Types.VARCHAR)
                .addValue("release_at", dialect.instantParam(referral.releaseAt))
                .addValue("settled_at", dialect.instantParam(referral.settledAt))
                .addValue("settled_by", referral.settledBy, Types.VARCHAR)
                .addValue("cancel_reason", referral.cancelReason, Types.VARCHAR)
                .addValue("flags", referral.flags.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }, Types.VARCHAR)
                .addValue("claim_network", referral.claimNetwork, Types.VARCHAR),
        ) == 1

    override fun referralOfInvitee(inviteeId: String, lock: Boolean): Referral? =
        jdbc.query("select * from campaign_referrals where invitee_id = :i" + if (lock) " for update" else "", mapOf("i" to inviteeId)) { rs, _ -> rs.toReferral() }.firstOrNull()

    override fun listReferrals(filter: ReferralFilter, after: ReferralPosition?, limit: Int): List<Referral> {
        val where = mutableListOf<String>()
        val params = MapSqlParameterSource().addValue("limit", limit)
        fun eq(column: String, name: String, value: String?) { if (value != null) { where += "$column = :$name"; params.addValue(name, value) } }
        eq("inviter_id", "inviter", filter.inviterId)
        eq("invitee_id", "invitee", filter.inviteeId)
        eq("campaign_id", "campaign", filter.campaignId)
        eq("status", "status", filter.status?.name)
        filter.flag?.let { flag -> where += "concat(',', flags, ',') like :flag"; params.addValue("flag", "%,${flag.name},%") }
        if (after != null) {
            where += "(claimed_at < :afterAt or (claimed_at = :afterAt and invitee_id < :afterId))"
            params.addValue("afterAt", dialect.instantParam(after.claimedAt)).addValue("afterId", after.inviteeId)
        }
        val clause = if (where.isEmpty()) "" else "where ${where.joinToString(" and ")}"
        return jdbc.query("select * from campaign_referrals $clause order by claimed_at desc, invitee_id desc limit :limit", params) { rs, _ -> rs.toReferral() }
    }

    private fun ResultSet.toReferral() = Referral(
        getString("invitee_id"), getString("inviter_id"), getString("campaign_id"), ReferralStatus.valueOf(getString("status")),
        dialect.readInstant(this, "claimed_at")!!, dialect.readInstant(this, "qualified_at"),
        qualifyEvent = getString("qualify_event"), releaseAt = dialect.readInstant(this, "release_at"),
        settledAt = dialect.readInstant(this, "settled_at"), settledBy = getString("settled_by"), cancelReason = getString("cancel_reason"),
        flags = getString("flags")?.split(',')?.filter { it.isNotBlank() }?.map { ReferralFlag.valueOf(it) }?.toSet() ?: emptySet(),
        claimNetwork = getString("claim_network"),
    )

    override fun countReferrals(inviterId: String, campaignId: String?, status: ReferralStatus?, lock: Boolean): Long {
        if (lock) {
            // 집계는 `for update` 를 못 받는다(PostgreSQL) - 줄을 잠그고 읽어 센다. 잠금 읽기는 두 방언 모두 다른 트랜잭션이 방금 커밋한 값을 본다
            return jdbc.queryForList(
                "select invitee_id from campaign_referrals where inviter_id = :inviter and (:campaign is null or campaign_id = :campaign) and (:status is null or status = :status) for update",
                MapSqlParameterSource().addValue("inviter", inviterId).addValue("campaign", campaignId, java.sql.Types.VARCHAR).addValue("status", status?.name, java.sql.Types.VARCHAR),
                String::class.java,
            ).size.toLong()
        }
        return jdbc.queryForObject(
            "select count(*) from campaign_referrals where inviter_id = :inviter and (:campaign is null or campaign_id = :campaign) and (:status is null or status = :status)",
            MapSqlParameterSource().addValue("inviter", inviterId).addValue("campaign", campaignId, java.sql.Types.VARCHAR).addValue("status", status?.name, java.sql.Types.VARCHAR),
            Long::class.java,
        ) ?: 0L
    }

    override fun countReferralsIn(inviterId: String, campaignId: String?, statuses: Set<ReferralStatus>, lock: Boolean): Long {
        val params = MapSqlParameterSource().addValue("inviter", inviterId).addValue("campaign", campaignId, Types.VARCHAR).addValue("statuses", statuses.map { it.name })
        val where = "where inviter_id = :inviter and (:campaign is null or campaign_id = :campaign) and status in (:statuses)"
        if (lock) return jdbc.queryForList("select invitee_id from campaign_referrals $where for update", params, String::class.java).size.toLong()   // countReferrals(lock) 와 같은 이유
        return jdbc.queryForObject("select count(*) from campaign_referrals $where", params, Long::class.java) ?: 0L
    }

    override fun lockAccounts(accountIds: Collection<String>) {
        // 정렬된 순서로 하나씩 - 두 트랜잭션이 같은 두 계정을 잡아도 같은 순서라 서로를 기다리지 않는다
        accountIds.toSortedSet().forEach { jdbc.queryForList("select account_id from campaign_referral_codes where account_id = :a for update", mapOf("a" to it), String::class.java) }
    }

    override fun transitionReferral(
        inviteeId: String,
        from: Set<ReferralStatus>,
        to: ReferralStatus,
        at: Instant,
        releaseAt: Instant?,
        qualifyEvent: String?,
        settledBy: String?,
        cancelReason: String?,
    ): Boolean {
        val ended = to == ReferralStatus.QUALIFIED || to == ReferralStatus.CAPPED || to == ReferralStatus.CANCELED
        val sets = mutableListOf("status = :to", "qualified_at = :qualified", "settled_at = :settled", "settled_by = :by", "cancel_reason = :reason")
        val params = MapSqlParameterSource()
            .addValue("i", inviteeId).addValue("from", from.map { it.name }).addValue("to", to.name)
            .addValue("qualified", dialect.instantParam(if (to == ReferralStatus.QUALIFIED) at else null))
            .addValue("settled", dialect.instantParam(if (ended) at else null))
            .addValue("by", if (ended) settledBy else null, Types.VARCHAR)
            .addValue("reason", if (to == ReferralStatus.CANCELED) cancelReason else null, Types.VARCHAR)
        if (releaseAt != null) { sets += "release_at = :release"; params.addValue("release", dialect.instantParam(releaseAt)) }
        if (qualifyEvent != null) { sets += "qualify_event = :event"; params.addValue("event", qualifyEvent) }
        return jdbc.update("update campaign_referrals set ${sets.joinToString(", ")} where invitee_id = :i and status in (:from)", params) == 1
    }

    override fun dueHeldReferrals(now: Instant, limit: Int): List<String> =
        jdbc.queryForList(
            "select invitee_id from campaign_referrals where status = 'HELD' and release_at <= :now order by release_at, invitee_id limit :limit",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("limit", limit),
            String::class.java,
        ).filterNotNull()

    override fun recordInviterNetwork(accountId: String, network: String, at: Instant) {
        jdbc.update("update campaign_referral_codes set last_network = :n, last_seen_at = :at where account_id = :a", mapOf("a" to accountId, "n" to network, "at" to dialect.instantParam(at)))
    }

    override fun networkSeen(inviterId: String, network: String, since: Instant): Boolean {
        val p = mapOf("inviter" to inviterId, "n" to network, "since" to dialect.instantParam(since))
        val last = jdbc.queryForObject("select count(*) from campaign_referral_codes where account_id = :inviter and last_network = :n and last_seen_at >= :since", p, Long::class.java) ?: 0L
        if (last > 0) return true
        return (jdbc.queryForObject("select count(*) from campaign_referrals where inviter_id = :inviter and claim_network = :n and claimed_at >= :since", p, Long::class.java) ?: 0L) > 0
    }

    override fun clearNetworksBefore(before: Instant): Int {
        val p = mapOf("before" to dialect.instantParam(before))
        return jdbc.update("update campaign_referral_codes set last_network = null, last_seen_at = null where last_network is not null and last_seen_at < :before", p) +
            jdbc.update("update campaign_referrals set claim_network = null where claim_network is not null and claimed_at < :before", p)
    }

    override fun eraseAccount(accountId: String, tombstone: String) {
        val p = mapOf("a" to accountId)
        jdbc.update("delete from campaign_attendance where account_id = :a", p)
        jdbc.update("delete from campaign_progress where account_id = :a", p)
        jdbc.update("delete from campaign_referral_codes where account_id = :a", p)
        jdbc.update("delete from campaign_referrals where invitee_id = :a", p)
        // 초대자 쪽 줄은 지우지 않는다 - 초대받은 사람의 「평생 한 번」이 풀리면 안 된다. 개인 식별만 끊는다
        jdbc.update("update campaign_referrals set inviter_id = :t where inviter_id = :a", mapOf("a" to accountId, "t" to tombstone))
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
