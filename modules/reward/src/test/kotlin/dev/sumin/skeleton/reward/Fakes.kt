package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import java.time.Instant
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger

/** 메모리 저장소 대역 - 트랜잭션은 없다(예외가 와도 되돌리지 않는다). 시험은 상태 전이가 한 번씩만 일어나는지만 본다 */
class FakeGrantStore : RewardGrantStore {
    val rows = TreeMap<String, RewardGrantRecord>(reverseOrder())
    var open = true

    override fun hasOpenTransaction() = open
    override fun <T> inTransaction(block: () -> T): T = block()

    override fun insertIfAbsent(grant: NewRewardGrant): Boolean {
        if (rows.values.any { it.sourceType == grant.sourceType && it.sourceKey == grant.sourceKey }) return false
        rows[grant.id] = RewardGrantRecord(grant.id, grant.accountId, grant.type, grant.params, grant.sourceType, grant.sourceKey, RewardGrantStatus.PENDING, null, null, grant.createdAt, null)
        return true
    }

    override fun lockPending(id: String): RewardGrantView? =
        rows[id]?.takeIf { it.status == RewardGrantStatus.PENDING }?.let { RewardGrantView(it.id, it.accountId, it.type, it.params, it.sourceType, it.sourceKey) }

    override fun markIssued(id: String, resultRef: String, at: Instant) {
        rows[id] = rows.getValue(id).copy(status = RewardGrantStatus.ISSUED, resultRef = resultRef, issuedAt = at)
    }

    private fun move(id: String, from: RewardGrantStatus, to: RewardGrantStatus, error: String? = null): Boolean {
        val row = rows[id]?.takeIf { it.status == from } ?: return false
        rows[id] = row.copy(status = to, lastError = error)
        return true
    }

    override fun markFailed(id: String, error: String) = move(id, RewardGrantStatus.PENDING, RewardGrantStatus.FAILED, error)
    override fun reopen(id: String) = move(id, RewardGrantStatus.FAILED, RewardGrantStatus.PENDING)
    override fun cancel(id: String) = move(id, RewardGrantStatus.PENDING, RewardGrantStatus.CANCELED)
    override fun find(id: String) = rows[id]

    override fun page(status: RewardGrantStatus?, before: String?, limit: Int) =
        rows.values.filter { (status == null || it.status == status) && (before == null || it.id < before) }.take(limit)

    override fun deleteByAccount(accountId: String): Int {
        val ids = rows.values.filter { it.accountId == accountId }.map { it.id }
        ids.forEach(rows::remove)
        return ids.size
    }
}

class RecordingTasks : RewardTasks {
    val enqueued = mutableListOf<String>()
    override fun enqueue(grantId: String) {
        enqueued += grantId
    }
}

/** [failures] 번 던진 뒤 성공하는 지급기. 성공 횟수를 센다 */
class ScriptedIssuer(override val type: String = "point", var failures: Int = 0, private val permanent: Boolean = false) : RewardIssuer {
    val issued = AtomicInteger()
    override fun validate(params: Map<String, String>) = Unit
    override fun issue(grant: RewardGrantView): IssuedReward {
        if (failures > 0) {
            failures--
            if (permanent) throw dev.sumin.skeleton.common.reward.RewardPermanentFailure("definition archived")
            error("boom")
        }
        issued.incrementAndGet()
        return IssuedReward("res_${grant.id}")
    }
    override fun describe(params: Map<String, String>, locale: Locale) = RewardLabel("Points", "You got ${params["amount"]} points", "/rewards/points")
}
