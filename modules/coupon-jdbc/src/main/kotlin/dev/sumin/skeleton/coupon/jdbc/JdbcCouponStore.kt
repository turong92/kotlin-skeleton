package dev.sumin.skeleton.coupon.jdbc

import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.coupon.Coupon
import dev.sumin.skeleton.coupon.CouponCodeRecord
import dev.sumin.skeleton.coupon.CouponDefinition
import dev.sumin.skeleton.coupon.CouponDefinitionStatus
import dev.sumin.skeleton.coupon.CouponScope
import dev.sumin.skeleton.coupon.CouponSource
import dev.sumin.skeleton.coupon.CouponStatus
import dev.sumin.skeleton.coupon.CouponStore
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 단계 한 줄의 저장 모양 - 도메인 타입의 검사(`init`)를 거치지 않고 읽고 쓰는 날값 */
data class StageDoc(val type: String = "FREE", val value: Long = 0, val maxDiscount: Long? = null, val periods: Int? = null)

data class ScopeDoc(
    val purposes: List<String> = emptyList(),
    val itemTypes: List<String> = emptyList(),
    val itemRefs: List<String> = emptyList(),
    val planCodes: List<String> = emptyList(),
)

/**
 * 쿠폰 표 넷(`coupon_definitions` · `coupons` · `coupon_codes` · `coupon_code_redemptions`). 상태 전이는 모두 `where status = …` 조건부 갱신이라 동시에 와도 한쪽만 이긴다.
 * 넣기는 [SqlDialect.insertIgnore](PG 에서 유니크 위반 예외는 호출자 트랜잭션 전체를 abort 시킨다)이고 **넣었는지는 내가 만든 id 가 있는지로 본다**
 * (MySQL 의 `on duplicate key update` 는 이미 있는 행에도 1 을 돌려준다). 잠금 읽기(`for update`)는 두 DB 모두 현재 값을 읽는다.
 */
class JdbcCouponStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
    private val json: JsonCodec,
) : CouponStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }

    // 정의

    override fun insertDefinition(definition: CouponDefinition) {
        jdbc.update(
            """
            insert into coupon_definitions (id, name, status, stages_json, currency, min_amount, scope_json, first_purchase_only, valid_days, valid_until,
                issue_limit, issued_count, per_account_limit, created_at, updated_at)
            values (:id, :name, :status, :stages, :currency, :min_amount, :scope, :first_purchase_only, :valid_days, :valid_until,
                :issue_limit, :issued_count, :per_account_limit, :created_at, :updated_at)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", definition.id)
                .addValue("name", definition.name)
                .addValue("status", definition.status.name)
                .addValue("stages", json.canonicalString(json.toDocument(definition.stages.map { StageDoc(it.type.name, it.value, it.maxDiscount, it.periods) })))
                .addValue("currency", definition.currency, Types.VARCHAR)
                .addValue("min_amount", definition.minAmount, Types.BIGINT)
                .addValue("scope", json.canonicalString(json.toDocument(ScopeDoc(definition.scope.purposes.map { it.name }, definition.scope.itemTypes, definition.scope.itemRefs, definition.scope.planCodes))))
                .addValue("first_purchase_only", definition.firstPurchaseOnly)
                .addValue("valid_days", definition.validDays, Types.INTEGER)
                .addValue("valid_until", instant(definition.validUntil))
                .addValue("issue_limit", definition.issueLimit, Types.INTEGER)
                .addValue("issued_count", definition.issuedCount)
                .addValue("per_account_limit", definition.perAccountLimit)
                .addValue("created_at", instant(definition.createdAt))
                .addValue("updated_at", instant(definition.updatedAt)),
        )
    }

    override fun findDefinition(id: String): CouponDefinition? =
        jdbc.query("select * from coupon_definitions where id = :id", mapOf("id" to id)) { rs, _ -> rs.toDefinition() }.firstOrNull()

    override fun lockDefinition(id: String): CouponDefinition? =
        jdbc.query("select * from coupon_definitions where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.toDefinition() }.firstOrNull()

    override fun findDefinitions(ids: Collection<String>): Map<String, CouponDefinition> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query("select * from coupon_definitions where id in (:ids)", mapOf("ids" to ids.toList())) { rs, _ -> rs.toDefinition() }.associateBy { it.id }
    }

    override fun listDefinitions(limit: Int): List<CouponDefinition> =
        jdbc.query("select * from coupon_definitions order by id desc limit :limit", mapOf("limit" to limit)) { rs, _ -> rs.toDefinition() }

    override fun updateDefinitionStatus(id: String, status: CouponDefinitionStatus, at: Instant): Boolean =
        jdbc.update("update coupon_definitions set status = :status, updated_at = :at where id = :id", mapOf("id" to id, "status" to status.name, "at" to instant(at))) == 1

    override fun incrementIssued(id: String, at: Instant) {
        jdbc.update("update coupon_definitions set issued_count = issued_count + 1, updated_at = :at where id = :id", mapOf("id" to id, "at" to instant(at)))
    }

    // 쿠폰

    override fun insertCouponIfAbsent(coupon: Coupon): Boolean {
        jdbc.update(
            dialect.insertIgnore("coupons", COUPON_COLUMNS, listOf("source_type", "source_ref")),
            MapSqlParameterSource()
                .addValue("id", coupon.id)
                .addValue("definition_id", coupon.definitionId)
                .addValue("account_id", coupon.accountId)
                .addValue("status", coupon.status.name)
                .addValue("holder_ref", coupon.holderRef, Types.VARCHAR)
                .addValue("source_type", coupon.sourceType.name)
                .addValue("source_ref", coupon.sourceRef)
                .addValue("expires_at", instant(coupon.expiresAt))
                .addValue("used_at", instant(coupon.usedAt))
                .addValue("created_at", instant(coupon.createdAt))
                .addValue("updated_at", instant(coupon.updatedAt)),
        )
        return jdbc.queryForObject("select count(*) from coupons where id = :id", mapOf("id" to coupon.id), Long::class.java) == 1L
    }

    override fun findCoupon(id: String): Coupon? =
        jdbc.query("select * from coupons where id = :id", mapOf("id" to id)) { rs, _ -> rs.toCoupon() }.firstOrNull()

    override fun lockBySource(sourceType: CouponSource, sourceRef: String): Coupon? =
        jdbc.query(
            "select * from coupons where source_type = :type and source_ref = :ref for update",
            mapOf("type" to sourceType.name, "ref" to sourceRef),
        ) { rs, _ -> rs.toCoupon() }.firstOrNull()

    override fun lockByAccountAndDefinition(accountId: String, definitionId: String): List<Coupon> =
        jdbc.query(
            "select * from coupons where account_id = :a and definition_id = :d for update",
            mapOf("a" to accountId, "d" to definitionId),
        ) { rs, _ -> rs.toCoupon() }

    override fun listByAccount(accountId: String, limit: Int): List<Coupon> =
        jdbc.query("select * from coupons where account_id = :a order by id desc limit :limit", mapOf("a" to accountId, "limit" to limit)) { rs, _ -> rs.toCoupon() }

    override fun findByHolder(holderRef: String): Coupon? =
        jdbc.query("select * from coupons where holder_ref = :h", mapOf("h" to holderRef)) { rs, _ -> rs.toCoupon() }.firstOrNull()

    override fun reserve(id: String, accountId: String, holderRef: String, now: Instant): Boolean =
        jdbc.update(
            "update coupons set status = 'RESERVED', holder_ref = :h, updated_at = :now " +
                "where id = :id and account_id = :a and status = 'AVAILABLE' and (expires_at is null or expires_at > :now)",
            mapOf("id" to id, "a" to accountId, "h" to holderRef, "now" to instant(now)),
        ) == 1

    override fun confirm(holderRef: String, now: Instant): Boolean =
        jdbc.update(
            "update coupons set status = 'USED', used_at = :now, updated_at = :now where holder_ref = :h and status in ('RESERVED', 'AVAILABLE', 'EXPIRED')",
            mapOf("h" to holderRef, "now" to instant(now)),
        ) == 1

    override fun release(holderRef: String, now: Instant): Boolean = back(holderRef, "RESERVED", now)

    override fun restore(holderRef: String, now: Instant): Boolean = back(holderRef, "USED", now)

    override fun revoke(id: String, now: Instant): Boolean =
        jdbc.update("update coupons set status = 'REVOKED', updated_at = :now where id = :id and status = 'AVAILABLE'", mapOf("id" to id, "now" to instant(now))) == 1

    /** [from] 상태로 이 주문이 쥔 쿠폰을 `AVAILABLE` 로 - 기한이 지났으면 `EXPIRED` */
    private fun back(holderRef: String, from: String, now: Instant): Boolean =
        jdbc.update(
            // used_at 을 status 보다 먼저 쓴다 - MySQL 은 set 절을 왼쪽부터 평가해 status 를 먼저 바꾸면 used_at 이 옛 상태를 못 본다
            "update coupons set used_at = case when status = 'USED' then null else used_at end, " +
                "status = case when expires_at is not null and expires_at <= :now then 'EXPIRED' else 'AVAILABLE' end, updated_at = :now where holder_ref = :h and status = :from",
            mapOf("h" to holderRef, "from" to from, "now" to instant(now)),
        ) == 1

    // 공용 코드

    override fun insertCode(code: CouponCodeRecord) {
        jdbc.update(
            "insert into coupon_codes (code, definition_id, max_redemptions, redeemed_count, expires_at, created_at) values (:code, :d, :max, :count, :expires, :created)",
            MapSqlParameterSource()
                .addValue("code", code.code)
                .addValue("d", code.definitionId)
                .addValue("max", code.maxRedemptions, Types.INTEGER)
                .addValue("count", code.redeemedCount)
                .addValue("expires", instant(code.expiresAt))
                .addValue("created", instant(code.createdAt)),
        )
    }

    override fun findCode(code: String): CouponCodeRecord? =
        jdbc.query("select * from coupon_codes where code = :c", mapOf("c" to code)) { rs, _ -> rs.toCode() }.firstOrNull()

    override fun lockCode(code: String): CouponCodeRecord? =
        jdbc.query("select * from coupon_codes where code = :c for update", mapOf("c" to code)) { rs, _ -> rs.toCode() }.firstOrNull()

    override fun hasRedeemed(code: String, accountId: String): Boolean =
        jdbc.queryForList("select code from coupon_code_redemptions where code = :c and account_id = :a for update", mapOf("c" to code, "a" to accountId), String::class.java).isNotEmpty()

    override fun insertRedemption(code: String, accountId: String) {
        jdbc.update("insert into coupon_code_redemptions (code, account_id) values (:c, :a)", mapOf("c" to code, "a" to accountId))
    }

    override fun incrementRedeemed(code: String) {
        jdbc.update("update coupon_codes set redeemed_count = redeemed_count + 1 where code = :c", mapOf("c" to code))
    }

    // 탈퇴

    override fun deleteByAccount(accountId: String): Int =
        jdbc.update("delete from coupons where account_id = :a", mapOf("a" to accountId)) +
            jdbc.update("delete from coupon_code_redemptions where account_id = :a", mapOf("a" to accountId))

    // 읽기

    private fun instant(value: Instant?): Any? = dialect.instantParam(value)

    private fun ResultSet.toDefinition(): CouponDefinition {
        val stages = json.fromDocument(json.parse(getString("stages_json")), Array<StageDoc>::class).map {
            DiscountStage(DiscountStageType.valueOf(it.type), it.value, it.maxDiscount, it.periods)
        }
        val scope = json.fromDocument(json.parse(getString("scope_json")), ScopeDoc::class)
        return CouponDefinition(
            id = getString("id"),
            name = getString("name"),
            status = CouponDefinitionStatus.valueOf(getString("status")),
            stages = stages,
            currency = getString("currency"),
            minAmount = getLong("min_amount").takeUnless { wasNull() },
            scope = CouponScope(scope.purposes.map { DiscountPurpose.valueOf(it) }, scope.itemTypes, scope.itemRefs, scope.planCodes),
            firstPurchaseOnly = getBoolean("first_purchase_only"),
            validDays = getInt("valid_days").takeUnless { wasNull() },
            validUntil = dialect.readInstant(this, "valid_until"),
            issueLimit = getInt("issue_limit").takeUnless { wasNull() },
            issuedCount = getInt("issued_count"),
            perAccountLimit = getInt("per_account_limit"),
            createdAt = dialect.readInstant(this, "created_at")!!,
            updatedAt = dialect.readInstant(this, "updated_at")!!,
        )
    }

    private fun ResultSet.toCoupon() = Coupon(
        id = getString("id"),
        definitionId = getString("definition_id"),
        accountId = getString("account_id"),
        status = CouponStatus.valueOf(getString("status")),
        holderRef = getString("holder_ref"),
        sourceType = CouponSource.valueOf(getString("source_type")),
        sourceRef = getString("source_ref"),
        expiresAt = dialect.readInstant(this, "expires_at"),
        usedAt = dialect.readInstant(this, "used_at"),
        createdAt = dialect.readInstant(this, "created_at")!!,
        updatedAt = dialect.readInstant(this, "updated_at")!!,
    )

    private fun ResultSet.toCode() = CouponCodeRecord(
        code = getString("code"),
        definitionId = getString("definition_id"),
        maxRedemptions = getInt("max_redemptions").takeUnless { wasNull() },
        redeemedCount = getInt("redeemed_count"),
        expiresAt = dialect.readInstant(this, "expires_at"),
        createdAt = dialect.readInstant(this, "created_at")!!,
    )

    private companion object {
        val COUPON_COLUMNS = listOf("id", "definition_id", "account_id", "status", "holder_ref", "source_type", "source_ref", "expires_at", "used_at", "created_at", "updated_at")
    }
}
