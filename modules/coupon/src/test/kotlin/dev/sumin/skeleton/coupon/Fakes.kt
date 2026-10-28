package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

class MutableTime(var current: Instant = Instant.parse("2026-10-28T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = current
}

/** 메모리 저장소 대역 - 트랜잭션은 모니터 하나라 동시 호출이 줄을 선다(되돌리기는 없다). 조건부 갱신의 뜻은 JDBC 구현과 같게 쓴다 */
class FakeCouponStore : CouponStore {
    val definitions = LinkedHashMap<String, CouponDefinition>()
    val coupons = LinkedHashMap<String, Coupon>()
    val codes = LinkedHashMap<String, CouponCodeRecord>()
    val redemptions = LinkedHashSet<Pair<String, String>>()

    override fun <T> inTransaction(block: () -> T): T = synchronized(this) { block() }

    override fun insertDefinition(definition: CouponDefinition) {
        definitions[definition.id] = definition
    }

    override fun findDefinition(id: String) = definitions[id]
    override fun lockDefinition(id: String) = definitions[id]
    override fun findDefinitions(ids: Collection<String>) = definitions.filterKeys { it in ids }
    override fun listDefinitions(limit: Int) = definitions.values.sortedByDescending { it.id }.take(limit)

    override fun updateDefinitionStatus(id: String, status: CouponDefinitionStatus, at: Instant): Boolean {
        val row = definitions[id] ?: return false
        definitions[id] = row.copy(status = status, updatedAt = at)
        return true
    }

    override fun incrementIssued(id: String, at: Instant) {
        definitions[id] = definitions.getValue(id).let { it.copy(issuedCount = it.issuedCount + 1, updatedAt = at) }
    }

    private fun effective(c: Coupon, now: Instant) = CouponExpiry.statusAt(c, now)

    override fun countByStatus(definitionId: String, now: Instant) =
        coupons.values.filter { it.definitionId == definitionId }.groupingBy { effective(it, now) }.eachCount()

    override fun usedCouponIds(definitionId: String, after: String?, limit: Int) =
        coupons.values.filter { it.definitionId == definitionId && it.status == CouponStatus.USED && (after == null || it.id > after) }.map { it.id }.sorted().take(limit)

    override fun pageByDefinition(definitionId: String, status: CouponStatus?, before: String?, limit: Int, now: Instant) =
        coupons.values.filter { it.definitionId == definitionId && (status == null || effective(it, now) == status) && (before == null || it.id < before) }
            .sortedByDescending { it.id }.take(limit)

    override fun insertCouponIfAbsent(coupon: Coupon): Boolean {
        if (coupons.values.any { it.sourceType == coupon.sourceType && it.sourceRef == coupon.sourceRef }) return false
        coupons[coupon.id] = coupon
        return true
    }

    override fun findCoupon(id: String) = coupons[id]
    override fun lockBySource(sourceType: CouponSource, sourceRef: String) = coupons.values.firstOrNull { it.sourceType == sourceType && it.sourceRef == sourceRef }
    override fun lockByAccountAndDefinition(accountId: String, definitionId: String) = coupons.values.filter { it.accountId == accountId && it.definitionId == definitionId }
    override fun listByAccount(accountId: String, limit: Int) = coupons.values.filter { it.accountId == accountId }.sortedByDescending { it.id }.take(limit)
    override fun findByHolder(holderRef: String) = coupons.values.firstOrNull { it.holderRef == holderRef }

    private fun update(holderRef: String, from: Set<CouponStatus>, now: Instant, next: (Coupon) -> Coupon): Boolean {
        val row = coupons.values.firstOrNull { it.holderRef == holderRef && it.status in from } ?: return false
        coupons[row.id] = next(row).copy(updatedAt = now)
        return true
    }

    override fun reserve(id: String, accountId: String, holderRef: String, now: Instant): Boolean {
        val row = coupons[id]?.takeIf { it.accountId == accountId && it.status == CouponStatus.AVAILABLE && !CouponExpiry.isExpired(it.expiresAt, now) } ?: return false
        coupons[id] = row.copy(status = CouponStatus.RESERVED, holderRef = holderRef, updatedAt = now)
        return true
    }

    override fun confirm(holderRef: String, now: Instant) =
        update(holderRef, setOf(CouponStatus.RESERVED, CouponStatus.AVAILABLE, CouponStatus.EXPIRED), now) { it.copy(status = CouponStatus.USED, usedAt = now) }

    private fun back(holderRef: String, from: CouponStatus, now: Instant) =
        update(holderRef, setOf(from), now) { it.copy(status = if (CouponExpiry.isExpired(it.expiresAt, now)) CouponStatus.EXPIRED else CouponStatus.AVAILABLE, usedAt = null) }

    override fun release(holderRef: String, now: Instant) = back(holderRef, CouponStatus.RESERVED, now)
    override fun restore(holderRef: String, now: Instant) = back(holderRef, CouponStatus.USED, now)

    override fun revoke(id: String, now: Instant): Boolean {
        val row = coupons[id]?.takeIf { it.status == CouponStatus.AVAILABLE } ?: return false
        coupons[id] = row.copy(status = CouponStatus.REVOKED, updatedAt = now)
        return true
    }

    override fun insertCode(code: CouponCodeRecord) {
        codes[code.code] = code
    }

    override fun findCode(code: String) = codes[code]
    override fun lockCode(code: String) = codes[code]
    override fun hasRedeemed(code: String, accountId: String) = (code to accountId) in redemptions
    override fun insertRedemption(code: String, accountId: String) {
        redemptions += code to accountId
    }

    override fun incrementRedeemed(code: String) {
        codes[code] = codes.getValue(code).let { it.copy(redeemedCount = it.redeemedCount + 1) }
    }

    override fun anonymizeAccount(accountId: String, tombstone: String, now: Instant): Int {
        val mine = coupons.values.filter { it.accountId == accountId }
        mine.forEach { coupons[it.id] = it.copy(accountId = tombstone, status = if (it.status == CouponStatus.AVAILABLE) CouponStatus.REVOKED else it.status, updatedAt = now) }
        redemptions.removeIf { it.second == accountId }
        return mine.size
    }
}

val NOW: Instant = Instant.parse("2026-10-28T00:00:00Z")

fun definition(
    id: String = "cpd_1",
    stages: List<DiscountStage> = listOf(DiscountStage(DiscountStageType.PERCENT, 30, null, 1)),
    status: CouponDefinitionStatus = CouponDefinitionStatus.ACTIVE,
    currency: String? = null,
    minAmount: Long? = null,
    scope: CouponScope = CouponScope(),
    firstPurchaseOnly: Boolean = false,
    issueLimit: Int? = null,
    perAccountLimit: Int = 1,
    issueStartsAt: Instant? = null,
    issueEndsAt: Instant? = null,
    validFrom: Instant? = null,
) = CouponDefinition(id, "30% off", status, stages, currency, minAmount, scope, firstPurchaseOnly, null, null, issueLimit, 0, perAccountLimit, NOW, NOW, issueStartsAt, issueEndsAt, validFrom)

fun coupon(
    id: String = "cpn_1",
    definitionId: String = "cpd_1",
    accountId: String = "acc_1",
    status: CouponStatus = CouponStatus.AVAILABLE,
    expiresAt: Instant? = null,
    holderRef: String? = null,
) = Coupon(id, definitionId, accountId, status, holderRef, CouponSource.ADMIN, "ref_$id", expiresAt, null, NOW, NOW)
