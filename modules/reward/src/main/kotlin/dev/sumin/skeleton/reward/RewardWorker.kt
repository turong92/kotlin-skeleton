package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import dev.sumin.skeleton.common.time.TimeProvider
import java.util.Locale
import org.slf4j.LoggerFactory

/**
 * 지급 건 하나를 지급기로 준다 — 한 트랜잭션에서 건을 잠그고 `PENDING` 이 아니면 끝, 지급기를 부르고 `ISSUED` 로 적는다. 알림은 **커밋 뒤**.
 * 지급기가 `IssuedReward(notify=false)`(새로 준 것이 없음 - 이미 가진 배지)를 돌려주면 알림을 건너뛴다.
 * 알림 문구는 **받는 사람의 계정 언어**(`AccountFacts.locale`)로 만든다 - JVM 기본 언어(운영 리눅스는 en)로 한국 계정에 영어가 가지 않게. 모르면 [locale].
 * 던지면 트랜잭션이 되돌아가 건은 `PENDING` 그대로이고, 큐가 백오프로 다시 부른다(지급기가 `(GRANT, 건 id)` 로 멱등이라 두 번 주지 않는다).
 * [RewardPermanentFailure] 는 건을 `FAILED` 로 적고 다시 던진다 — 호출한 쪽(큐 처리기)이 재시도 없이 끝내게.
 */
class RewardWorker(
    private val store: RewardGrantStore,
    private val issuers: () -> List<RewardIssuer>,
    private val notifier: () -> RewardNotifier,
    private val time: TimeProvider,
    /** 받는 사람 언어를 아는 곳(`account`) - 없으면 [locale] */
    private val facts: () -> AccountFacts? = { null },
    /** 계정 언어를 모를 때 쓰는 언어 */
    private val locale: Locale = Locale.getDefault(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(grantId: String) {
        val done = try {
            store.inTransaction {
                val grant = store.lockPending(grantId) ?: return@inTransaction null
                val issuer = issuers().firstOrNull { it.type == grant.type }
                    ?: throw RewardPermanentFailure("no issuer for reward type '${grant.type}'")
                val issued = issuer.issue(grant)
                store.markIssued(grant.id, issued.resultRef, time.now())
                Triple(grant, issuer, issued.notify)
            }
        } catch (e: RewardPermanentFailure) {
            store.inTransaction { store.markFailed(grantId, e.message ?: "permanent failure") }
            throw e
        } ?: return
        val (grant, issuer, notify) = done
        if (!notify) return
        try {
            notifier().issued(grant, issuer.describe(grant.params, facts()?.locale(grant.accountId) ?: locale))
        } catch (e: Exception) {
            log.warn("reward notification failed grant={} exception={}", grant.id, e.javaClass.simpleName)   // 지급은 이미 끝났다
        }
    }
}
