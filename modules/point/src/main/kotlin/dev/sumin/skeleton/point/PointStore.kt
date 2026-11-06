package dev.sumin.skeleton.point

import java.time.Instant

/**
 * 원장 저장 포트. **각 메서드가 계정 단위로 원자적**이다 — 같은 계정의 동시 호출은 직렬로 돈다(잔액 줄을 잠근다). 호출자 트랜잭션이 있으면 거기에 합류한다.
 * 유효기간이 지난 묶음은 차감할 때 그 계정 몫이 먼저 소멸 처리되고 잔액에서도 빠진다(소멸 작업이 늦어도 못 쓴다).
 * 잔액이 모자란 차감의 [PointInsufficientException] 은 트랜잭션 밖에서 던진다 - 호출자 트랜잭션을 롤백 전용으로 만들지 않으니 잡고 계속해도 된다.
 */
interface PointStore {
    /**
     * 적립 묶음 하나를 더한다([kind] 는 `EARN` 또는 `ADJUST`). 같은 `(sourceType, sourceRef)` 가 이미 있으면 아무것도 하지 않고 null - 단 **같은 요청일 때만**(종류와 금액이 같다, 유효기간 차이는 보지 않는다). 종류나 금액이 다르면 [PointSourceConflictException] 이고 아무것도 바뀌지 않는다.
     * 이미 있는 줄이 다른 계정의 것이면 [IllegalStateException].
     */
    fun earn(
        accountId: String,
        amount: Long,
        expiresAt: Instant?,
        kind: PointEntryKind,
        sourceType: String,
        sourceRef: String,
        reason: String?,
        now: Instant,
    ): PointEntry?

    /**
     * [amount] 를 소멸이 가까운 묶음부터(유효기간 없는 것은 맨 뒤) 뺀다([kind] 는 `SPEND` 또는 `ADJUST`).
     * 잔액이 모자라면 [PointInsufficientException] 이고 아무것도 바뀌지 않는다. 같은 `(sourceType, sourceRef)` 가 이미 있으면 그 줄을 돌려준다(두 번 빼지 않는다) - 단 같은 요청일 때만(종류와 금액이 같다); 다르면 [PointSourceConflictException]. 두 예외 모두 트랜잭션 밖에서 던진다.
     */
    fun spend(
        accountId: String,
        amount: Long,
        kind: PointEntryKind,
        sourceType: String,
        sourceRef: String,
        reason: String?,
        now: Instant,
    ): PointEntry

    /**
     * 차감 `(spendSourceType, spendSourceRef)` 를 [amount] 만큼(null 이면 아직 안 되돌린 전부) 되돌린다 - 사용 취소(환원). 부분 취소를 여러 번 해도 된다.
     * 포인트는 그 차감이 뺀 **원래 묶음에 원래 유효기간으로** 돌아간다(유효기간이 늦은 묶음부터). 그 사이 유효기간이 지난 묶음 몫은 바로 `EXPIRE` 줄로 닫는다.
     * 자기 `(sourceType, sourceRef)` 로 멱등이다: 같은 요청이면 처음 `RESTORE` 줄을 돌려주고, 다른 요청(다른 차감 · 다른 금액)이면 [PointSourceConflictException].
     * 원 차감이 없으면(옛 차감 포함) [PointSpendNotFoundException], 취소 합계가 원 차감을 넘으면 [PointCancelExceededException] - 모두 트랜잭션 밖에서 던지고 아무것도 바뀌지 않는다.
     * 다른 계정의 차감이면 [IllegalStateException]. `amount` 가 null 인 재호출은 금액을 대조하지 않는다(취소 키마다 한 가지 뜻으로만 쓴다).
     * 직렬로 들어온 요청만 트랜잭션 밖에서 던진다 - 동시 요청 · 옛 스냅샷 경로 · 다른 계정의 차감은 예외로 롤백되니 다시 부르면 된다.
     */
    fun cancelSpend(
        accountId: String,
        spendSourceType: String,
        spendSourceRef: String,
        amount: Long?,
        sourceType: String,
        sourceRef: String,
        now: Instant,
    ): PointEntry

    fun balance(accountId: String, now: Instant, soonBefore: Instant): PointBalance

    /** 최신순. [after] 가 있으면 그 줄 다음부터 */
    fun history(accountId: String, after: PointCursor?, limit: Int): List<PointEntry>

    fun findBySource(sourceType: String, sourceRef: String): PointEntry?

    /** 유효기간이 [now] 를 지난 묶음의 남은 양을 `EXPIRE` 줄로 닫는다. 만든 `EXPIRE` 줄 수. 두 번 돌려도 한 번만 닫는다 */
    fun expireDue(now: Instant): Int

    /** 계정이 지워질 때 — 잔액과 모든 줄. 멱등 */
    fun eraseAccount(accountId: String)
}
