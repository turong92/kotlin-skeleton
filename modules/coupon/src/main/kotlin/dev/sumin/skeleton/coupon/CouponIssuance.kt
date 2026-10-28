package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider

/** 발급 결과 — [Issued] 는 장을 돌려준다(같은 출처가 이미 만든 장 포함), 나머지는 한도에 막힌 것 */
sealed interface IssueOutcome {
    data class Issued(val coupon: Coupon) : IssueOutcome

    /** 정의의 발급 한도를 다 썼다 */
    data object DefinitionLimitReached : IssueOutcome

    /** 이 계정이 이 정의로 받을 수 있는 장 수를 다 채웠다 */
    data object AccountLimitReached : IssueOutcome
}

data class AdminIssueResult(val issued: List<String>, val skipped: List<String>)

/**
 * 쿠폰 발급 — 정의 줄을 잠그고 한도(정의 · 계정당)를 센 뒤 한 장을 넣는다. 열린 트랜잭션이 있으면 거기에 합류하므로 지급기가 지급 건의 트랜잭션 안에서 불러도 된다.
 * 같은 `(sourceType, sourceRef)` 는 한 장으로 접힌다 — 일꾼이 같은 지급 건을 두 번 불러도 같은 장을 돌려준다.
 */
class CouponIssuance(
    private val store: CouponStore,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
) {
    /** 새로 만든 장(또는 같은 출처가 이미 만든 장). 한도에 막히면 null. 정의가 없거나 발급 중이 아니면 [CouponException] */
    fun issue(definitionId: String, accountId: String, sourceType: CouponSource, sourceRef: String): Coupon? =
        (outcome(definitionId, accountId, sourceType, sourceRef) as? IssueOutcome.Issued)?.coupon

    fun outcome(definitionId: String, accountId: String, sourceType: CouponSource, sourceRef: String): IssueOutcome = store.inTransaction {
        val now = time.now()
        val definition = store.lockDefinition(definitionId)
            ?: throw CouponException(CouponErrorCode.DEFINITION_NOT_FOUND, "coupon definition $definitionId not found")
        store.lockBySource(sourceType, sourceRef)?.let { return@inTransaction IssueOutcome.Issued(it) }

        val expiresAt = CouponExpiry.expiresAt(definition, now)
        if (definition.status != CouponDefinitionStatus.ACTIVE || CouponExpiry.isExpired(definition.validUntil, now)) {
            throw CouponException(CouponErrorCode.DEFINITION_NOT_ACTIVE, "coupon definition $definitionId is not issuing")
        }
        if (definition.issueLimit != null && definition.issuedCount >= definition.issueLimit) return@inTransaction IssueOutcome.DefinitionLimitReached
        if (store.lockByAccountAndDefinition(accountId, definitionId).size >= definition.perAccountLimit) return@inTransaction IssueOutcome.AccountLimitReached

        val coupon = Coupon(ids.newId(ID_PREFIX), definitionId, accountId, CouponStatus.AVAILABLE, null, sourceType, sourceRef, expiresAt, null, now, now)
        if (!store.insertCouponIfAbsent(coupon)) {
            return@inTransaction IssueOutcome.Issued(store.lockBySource(sourceType, sourceRef) ?: error("coupon source $sourceType/$sourceRef vanished"))
        }
        store.incrementIssued(definitionId, now)
        IssueOutcome.Issued(coupon)
    }

    /**
     * 운영자 발급 - 계정마다 새 장(한도에 막힌 계정은 건너뛴다). 정의가 없거나 발급 중이 아니면 전체가 거절된다.
     * [inTransaction] 은 같은 트랜잭션 안에서 결과와 함께 불린다(운영 기록용).
     */
    fun issueByAdmin(definitionId: String, accountIds: List<String>, inTransaction: (AdminIssueResult) -> Unit = {}): AdminIssueResult = store.inTransaction {
        val issued = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        accountIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { accountId ->
            if (outcome(definitionId, accountId, CouponSource.ADMIN, ids.newId(ADMIN_REF_PREFIX)) is IssueOutcome.Issued) issued += accountId else skipped += accountId
        }
        AdminIssueResult(issued, skipped).also(inTransaction)
    }

    companion object {
        const val ID_PREFIX = "cpn_"
        const val ADMIN_REF_PREFIX = "adm_"
    }
}
