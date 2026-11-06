package dev.sumin.skeleton.point

import java.time.Instant

/** 작업 기록에 남는 안전한 결과 코드 - `POINT.*` 오류 코드와 같은 이름 공간이다. 본문 · 예외 메시지는 싣지 않는다 */
object PointOperationCodes {
    const val OK = "OK"

    /** 응답을 못 받았거나 커밋 결과를 모른다 - 조회가 원장으로 가른다 */
    const val UNKNOWN = "POINT.UNKNOWN"

    /** 조회를 다 써도 원장 줄을 못 찾았다 */
    const val ABANDONED = "POINT.ABANDONED"

    /** 보상하려는 원 차감이 원장에 있다고 확인되지 않았다 - 보상하지 않고 기다린다 */
    const val ORIGIN_UNRESOLVED = "POINT.ORIGIN_UNRESOLVED"
}

/** 작업 하나의 결과 - [entry] 는 성공했을 때의 원장 줄. 확정 실패는 던지지 않고 [operation] 의 상태 · 코드로 돌려준다 */
data class PointOperationResult(val operation: PointOperation, val entry: PointEntry?) {
    val status: PointOperationStatus get() = operation.status
    val succeeded: Boolean get() = operation.status == PointOperationStatus.SUCCEEDED
}

/**
 * 기록되는 포인트 쓰기 - [PointWallet] 위의 **선택** 층이다(지갑을 직접 불러도 된다). 작업마다 접수 영수증 · 시도 · 결과 전이를 남긴다([PointOperationLog]).
 * 작업 하나 = `(sourceType, sourceRef)` 하나(원장 줄의 키와 같다): 같은 키의 재시도는 원 작업에 시도로 이어 붙고, 원장이 멱등이라 두 번째 줄은 생기지 않는다.
 *
 * - **활성 트랜잭션 안에서 부르면 [IllegalStateException] 으로 거절한다** - 호출자 트랜잭션에 합류하면 잠근 줄 때문에 자기 교착이 나고, 커넥션이 둘 필요하고, 원장 읽기가 낡은 스냅샷을 탄다.
 *   서버 안에서 다른 쓰기와 **한 트랜잭션**으로 묶어야 하면 [PointWallet] 을 쓴다(기록 없이).
 * - 영수증과 시도는 각자 짧은 트랜잭션으로 부수효과 **전에** 커밋한다. 영수증 쓰기가 실패하면 원장에 닿기 전에 던진다. 원장 트랜잭션이 실패해도 영수증과 실패 시도는 남는다.
 * - 성공 표시(보상이면 원 작업의 보상 완료까지)는 원장 쓰기와 **같은 트랜잭션**이다 - 그 트랜잭션이 롤백되면 성공으로 남지 않는다(작업은 불명으로 남고 조회가 원장으로 가른다).
 * - `actor` 는 호출한 **서비스 이름**이다(64자까지) - 사람 id · 계정 id 같은 개인 식별값을 넣지 않는다.
 * - 확정 실패(잔액 부족 · 취소 초과 · 키 충돌 …)는 던지지 않고 [PointOperationStatus.FAILED] 로 돌려준다. 같은 키에 **다른 요청**이면 [PointSourceConflictException].
 * - 결과를 모르는 예외(타임아웃 · 커밋 응답 유실)는 [PointOperationStatus.UNKNOWN] 으로 적고 그대로 던진다. 보상 전에 [resolve] 로 원장을 확인한다.
 */
interface PointOperations {
    fun spend(actor: String, accountId: String, amount: Long, sourceType: String, sourceRef: String): PointOperationResult

    fun earn(actor: String, accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointOperationResult

    /**
     * 사용 취소(환원) - [PointWallet.cancelSpend] 와 같다. 원 차감이 이 층에서 만든 작업이면 그 작업과 연결하고(보상), 원 작업이 **원장에 있다고 확인되기 전에는 보상하지 않는다**:
     * 원 작업이 성공이 아니면 먼저 [resolve] 로 원장을 보고, 그래도 줄이 없으면 이 작업은 [PointOperationCodes.ORIGIN_UNRESOLVED] 의 [PointOperationStatus.UNKNOWN] 으로 남는다(같은 취소 키로 나중에 다시).
     */
    fun cancelSpend(
        actor: String,
        accountId: String,
        spendSourceType: String,
        spendSourceRef: String,
        amount: Long?,
        sourceType: String,
        sourceRef: String,
    ): PointOperationResult

    /**
     * 끝나지 않은 작업(처리 중 · 불명 · 포기)을 원장으로 가른다: `(sourceType, sourceRef)` 의 줄이 이 요청과 맞게 **있으면** 성공으로 바로잡는다(포기한 건도 - 느리게 진행되던 원장 쓰기가 늦게 커밋되거나 지갑으로 같은 키를 직접 쓴 경우).
     * 없으면 처리 중 · 접수는 불명으로 올리고 그대로 둔다. 쓰기는 하지 않는다. 모르는 작업이면 null.
     */
    fun resolve(operationId: String): PointOperation?

    fun find(operationId: String): PointOperation?

    fun findBySource(sourceType: String, sourceRef: String): PointOperation?

    /** 조회를 다 썼다 - 아직 끝나지 않았으면 포기로 닫는다(이후 [resolve] 가 줄을 찾으면 성공으로 바로잡는다) */
    fun abandon(operationId: String)
}
