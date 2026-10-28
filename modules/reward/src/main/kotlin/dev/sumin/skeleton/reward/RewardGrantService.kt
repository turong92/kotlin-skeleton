package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider

class RewardGrantService(
    private val store: RewardGrantStore,
    private val tasks: RewardTasks,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
) : RewardGrants {
    override fun record(accountId: String, type: String, params: Map<String, String>, sourceType: String, sourceKey: String): String? {
        // 지급 건 줄과 작업이 호출자와 같은 커밋에 들어야 한다 - 밖에서 부르면 줄만 남거나 작업만 남는다
        check(store.hasOpenTransaction()) { "RewardGrants.record must be called inside the caller's transaction: the grant row and its job commit together" }
        val id = ids.newId(ID_PREFIX)
        if (!store.insertIfAbsent(NewRewardGrant(id, accountId, type, params, sourceType, sourceKey, time.now()))) return null
        tasks.enqueue(id)
        return id
    }

    /** `FAILED` 인 건을 다시 `PENDING` 으로 돌리고 작업을 넣는다. 그 밖의 상태는 409. [inTransaction] 은 같은 트랜잭션 안에서 불린다(운영 기록용) */
    fun retry(id: String, inTransaction: () -> Unit = {}) {
        store.inTransaction {
            requireExists(id)
            if (!store.reopen(id)) throw RewardException(RewardErrorCode.NOT_RETRYABLE, "grant $id is not failed")
            tasks.enqueue(id)
            inTransaction()
        }
    }

    /** `PENDING` 인 건을 취소한다. 그 밖의 상태는 409 */
    fun cancel(id: String) {
        store.inTransaction {
            requireExists(id)
            if (!store.cancel(id)) throw RewardException(RewardErrorCode.NOT_CANCELABLE, "grant $id is not pending")
        }
    }

    fun find(id: String): RewardGrantRecord? = store.find(id)

    fun page(status: RewardGrantStatus?, before: String?, size: Int): List<RewardGrantRecord> = store.page(status, before, size)

    private fun requireExists(id: String) {
        store.find(id) ?: throw RewardException(RewardErrorCode.NOT_FOUND, "grant $id not found")
    }

    companion object {
        const val ID_PREFIX = "grant_"
    }
}
