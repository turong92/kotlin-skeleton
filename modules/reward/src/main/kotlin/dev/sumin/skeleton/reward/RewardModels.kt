package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.reward.RewardGrantView
import java.time.Instant

/** 지급 건 상태 — `PENDING → ISSUED`(끝) · `PENDING → FAILED`(운영자 「다시 지급」으로 `PENDING`) · `PENDING → CANCELED`(운영자, 지급 전만) */
enum class RewardGrantStatus { PENDING, ISSUED, FAILED, CANCELED }

/** 새 지급 건. [sourceKey] 는 같은 조건이 두 번 판정돼도 한 건으로 접히게 하는 키(`<캠페인>:<계정>:<날>:daily:0` …) */
data class NewRewardGrant(
    val id: String,
    val accountId: String,
    val type: String,
    val params: Map<String, String>,
    val sourceType: String,
    val sourceKey: String,
    val createdAt: Instant,
)

data class RewardGrantRecord(
    val id: String,
    val accountId: String,
    val type: String,
    val params: Map<String, String>,
    val sourceType: String,
    val sourceKey: String,
    val status: RewardGrantStatus,
    val resultRef: String?,
    val lastError: String?,
    val createdAt: Instant,
    val issuedAt: Instant?,
)

/**
 * 이벤트 쪽이 아는 유일한 자리 — 지급 건을 기록하고 일꾼에게 넘긴다. **호출자 트랜잭션 안**에서 부른다: 지급 건 줄과 작업이 같은 커밋에 든다.
 */
interface RewardGrants {
    /** 새 건이면 id, 같은 [sourceType] · [sourceKey] 가 이미 있으면 null (지급은 이미 한 번 정해졌다) */
    fun record(accountId: String, type: String, params: Map<String, String>, sourceType: String, sourceKey: String): String?
}

/**
 * 지급 건 저장 포트 — `reward-jdbc` 가 구현한다. 쓰기 메서드는 모두 [inTransaction] 안(또는 호출자 트랜잭션 안)에서 부른다.
 */
interface RewardGrantStore {
    fun hasOpenTransaction(): Boolean

    /** 열린 트랜잭션이 있으면 거기에 합류하고, 없으면 새로 연다 */
    fun <T> inTransaction(block: () -> T): T

    /** 같은 [NewRewardGrant.sourceType] · [NewRewardGrant.sourceKey] 가 없을 때만 넣는다. 넣었으면 true */
    fun insertIfAbsent(grant: NewRewardGrant): Boolean

    /** `PENDING` 인 건을 잠그고 읽는다. 없거나 `PENDING` 이 아니면 null (다른 일꾼이 끝냈다) */
    fun lockPending(id: String): RewardGrantView?

    fun markIssued(id: String, resultRef: String, at: Instant)

    /** `PENDING` 일 때만 `FAILED` 로. 바꿨으면 true */
    fun markFailed(id: String, error: String): Boolean

    /** `FAILED` 일 때만 `PENDING` 으로(오류 문구를 지운다). 바꿨으면 true */
    fun reopen(id: String): Boolean

    /** `PENDING` 일 때만 `CANCELED` 로. 바꿨으면 true */
    fun cancel(id: String): Boolean

    fun find(id: String): RewardGrantRecord?

    /** 새것부터. [before] 는 직전 쪽의 마지막 id (없으면 처음부터) */
    fun page(status: RewardGrantStatus?, before: String?, limit: Int): List<RewardGrantRecord>

    /** 계정이 지워질 때 그 계정의 지급 건을 지운다. 지운 수 */
    fun deleteByAccount(accountId: String): Int
}

/** 지급 건 하나를 일꾼이 맡도록 작업을 넣는다 — 호출자 트랜잭션 안에서 */
fun interface RewardTasks {
    fun enqueue(grantId: String)
}

/** 지급이 끝난 뒤(커밋 뒤) 받는 사람에게 알린다. 던져도 지급은 그대로다 */
fun interface RewardNotifier {
    fun issued(grant: RewardGrantView, label: dev.sumin.skeleton.common.reward.RewardLabel)
}
