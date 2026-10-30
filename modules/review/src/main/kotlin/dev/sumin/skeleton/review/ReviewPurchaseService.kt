package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider

/**
 * [ReviewPurchases] 구현. 잠금 순서는 쓰기와 같다 - **리뷰 줄을 먼저** 잠그고(있을 때만) 구매 줄을 바꾼다(취소는 그 앞에 자기 구매 줄을 잠근다).
 * 그래서 같은 대상의 구매 둘을 동시에 취소해도 마지막 하나가 `verified` 를 내린다.
 */
class ReviewPurchaseService(
    private val store: ReviewStore,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
) : ReviewPurchases {
    override fun record(accountId: String, target: ReviewTarget, ref: String) {
        require(ref.isNotBlank() && ref.length <= MAX_REF) { "ref must be 1..$MAX_REF characters" }
        store.inTransaction {
            store.insertPurchaseIfAbsent(ReviewPurchase(ids.newId("rvp_"), accountId, target.type, target.id, ref, time.now(), null))
            store.lockByTarget(target, accountId)
            if (store.hasLivePurchase(accountId, target)) store.setVerified(accountId, target, true)
        }
    }

    override fun revoke(ref: String) {
        store.inTransaction {
            val purchase = store.lockPurchase(ref) ?: return@inTransaction
            store.lockByTarget(purchase.target, purchase.accountId)
            if (store.revokePurchase(ref, time.now()) && !store.hasLivePurchase(purchase.accountId, purchase.target)) {
                store.setVerified(purchase.accountId, purchase.target, false)
            }
        }
    }

    companion object {
        const val MAX_REF = 200
    }
}
