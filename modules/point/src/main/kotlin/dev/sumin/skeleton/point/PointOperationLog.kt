package dev.sumin.skeleton.point

import java.time.Instant

/** 작업 종류 - 원장 줄의 종류가 아니라 「앱이 무엇을 요청했는가」 */
enum class PointOperationKind { SPEND, CANCEL_SPEND, EARN }

/**
 * 작업 상태. 접수([ACCEPTED]) → 처리 중([PROCESSING]) → 성공([SUCCEEDED]) | 확정 실패([FAILED]) | 불명([UNKNOWN] - 응답을 못 받았거나 커밋 결과를 모른다, 조회 대기).
 * 조회를 다 써도 못 찾으면 [ABANDONED] - 그 뒤 원장 줄이 나타나면(느리게 진행되던 원장 쓰기가 늦게 커밋되거나 지갑으로 같은 키를 직접 쓴 경우) 조회(`resolve`)가 [SUCCEEDED] 로 바로잡는다.
 */
enum class PointOperationStatus { ACCEPTED, PROCESSING, SUCCEEDED, FAILED, UNKNOWN, ABANDONED }

/** 원 작업(차감)의 보상 상태 - 보상(사용 취소) 요청이 있었는가 */
enum class PointCompensation { NONE, REQUESTED, DONE, FAILED }

/**
 * 작업 하나의 기록 - 마지막 성공 줄이 아니라 접수 영수증과 전이를 남긴다. 토큰 · 결제 키 · 본문 · 사유 같은 값은 칸 자체가 없다.
 * [sourceType] · [sourceRef] 는 **원장 줄의 키와 같은 값**이다(작업 하나 = 키 하나). [resultCode] 는 `OK` 또는 `POINT.*` 코드처럼 안전한 문자열뿐이다.
 */
data class PointOperation(
    val operationId: String,
    val accountId: String,
    val kind: PointOperationKind,
    /** 요청한 금액. 남은 전부를 되돌리는 사용 취소는 null */
    val amount: Long?,
    val sourceType: String,
    val sourceRef: String,
    /** 요청 지문(종류 · 계정 · 금액 · 원 차감 키의 해시) - 같은 키에 다른 요청을 가른다 */
    val fingerprint: String,
    /** 호출한 쪽(서비스 이름이나 계정 id) - 문자열이면 된다 */
    val actor: String,
    val status: PointOperationStatus,
    /** 보상 작업이면 원 작업 */
    val originOperationId: String?,
    val compensation: PointCompensation,
    val resultEntryId: String?,
    val resultCode: String?,
    val attempts: Int,
    val acceptedAt: Instant,
    val lastAttemptAt: Instant?,
    val finishedAt: Instant?,
)

/** 시도 한 번 - [outcome] 은 끝나기 전에는 null */
data class PointOperationAttempt(
    val operationId: String,
    val n: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val outcome: String?,
    val errorCode: String?,
)

/** 영수증에 적을 것 */
data class PointReceipt(
    val accountId: String,
    val kind: PointOperationKind,
    val amount: Long?,
    val sourceType: String,
    val sourceRef: String,
    val fingerprint: String,
    val actor: String,
    val originOperationId: String? = null,
)

/** [PointOperationLog.accept] 의 답 - [created] 가 false 면 같은 키의 기존 작업이다 */
data class AcceptedOperation(val operation: PointOperation, val created: Boolean)

/**
 * 작업 기록 저장 포트 - 구현은 `point-jdbc`. 메서드마다 **활성 트랜잭션이 없으면 짧은 트랜잭션 하나를 열고, 있으면 합류한다**(새 트랜잭션을 강제하지 않는다 -
 * 잠근 줄을 같은 스레드의 다른 트랜잭션이 건드리면 자기 자신과 교착한다). 경계는 [PointOperations] 가 정한다:
 *  - [accept] · [beginAttempt] · [fail] · [markUnknown] · [abandon] · [recoverSucceeded]: 트랜잭션 밖에서 부른다 - 각자 짧은 트랜잭션이라 원장 트랜잭션이 실패해도 영수증과 실패 시도는 남는다.
 *  - [succeed] · [setCompensation]: 원장 쓰기를 하는 트랜잭션 **안에서** 부른다 - 원장과 함께 커밋되거나 함께 사라진다(롤백된 쓰기를 성공으로 적지 않는다).
 * 읽기는 트랜잭션 없이 한 문장씩이다.
 */
interface PointOperationLog {
    /** 영수증을 적는다. 같은 `(sourceType, sourceRef)` 가 이미 있으면 새로 만들지 않고 기존 작업을 돌려준다 */
    fun accept(receipt: PointReceipt, now: Instant): AcceptedOperation

    /** 시도 하나를 연다: 상태 PROCESSING, 시도 번호를 하나 올려 돌려준다. [reschedule] 이면(같은 키 재시도 - 영수증을 이번에 만들지 않았다) 조회 예약도 다시 건다(같은 트랜잭션) */
    fun beginAttempt(operationId: String, now: Instant, reschedule: Boolean = false): Int

    /** 성공 - 결과 줄과 함께 SUCCEEDED, 시도를 닫는다(불명으로 닫힌 시도도 덮는다). 원장 트랜잭션 안에서 부른다 */
    fun succeed(operationId: String, attempt: Int, resultEntryId: String, now: Instant)

    /** 확정 실패: FAILED + 안전한 오류 코드. 이미 SUCCEEDED 면 건드리지 않는다 */
    fun fail(operationId: String, attempt: Int, errorCode: String, now: Instant)

    /** 결과를 모른다: UNKNOWN + 코드. 이미 SUCCEEDED · FAILED 면 건드리지 않는다 */
    fun markUnknown(operationId: String, attempt: Int?, errorCode: String, now: Instant)

    /** 조회를 다 썼다: ABANDONED. 이미 SUCCEEDED · FAILED 면 건드리지 않는다 */
    fun abandon(operationId: String, now: Instant)

    /** 원장 줄이 있음을 확인했다: SUCCEEDED 로 바로잡는다 - UNKNOWN · ABANDONED · PROCESSING · ACCEPTED 에서만. 바꿨으면 true */
    fun recoverSucceeded(operationId: String, resultEntryId: String, now: Instant): Boolean

    /** 보상 상태 - 한 번 DONE 이 되면 바뀌지 않는다. 보상 완료는 환원을 쓴 원장 트랜잭션 안에서 부른다 */
    fun setCompensation(operationId: String, compensation: PointCompensation)

    fun find(operationId: String): PointOperation?

    fun findBySource(sourceType: String, sourceRef: String): PointOperation?

    fun attempts(operationId: String): List<PointOperationAttempt>
}
