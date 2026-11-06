package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 포인트 에러 코드 — `POINT.` 로 시작한다 (프론트는 이 문자열로 분기한다). 권한 부족 403 은 `COMMON.FORBIDDEN` */
enum class PointErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    INSUFFICIENT("POINT.INSUFFICIENT", HttpStatus.CONFLICT, "Not enough points"),
    SPEND_NOT_FOUND("POINT.SPEND_NOT_FOUND", HttpStatus.NOT_FOUND, "No such deduction to cancel"),
    CANCEL_EXCEEDED("POINT.CANCEL_EXCEEDED", HttpStatus.CONFLICT, "Cancellation exceeds the deduction"),
    SOURCE_CONFLICT("POINT.SOURCE_CONFLICT", HttpStatus.CONFLICT, "Source key already used for a different request"),
}

/** 잔액보다 큰 차감(운영자 조정 포함) — 아무것도 바뀌지 않는다 */
class PointInsufficientException(needed: Long, available: Long) :
    ApplicationException("Not enough points: needed $needed, available $available", PointErrorCode.INSUFFICIENT)

/**
 * 같은 `(sourceType, sourceRef)` 로 **다른 요청**(종류나 금액이 다름)이 왔다 - 멱등 키 규칙(같은 키에 다른 요청이면 거절). 첫 줄을 돌려주면 호출자가 이번 요청이 반영됐다고 믿는다.
 * 아무것도 바뀌지 않고, 트랜잭션 밖에서 던진다 - 호출자 트랜잭션을 롤백 전용으로 만들지 않는다. 유효기간 차이는 보지 않는다.
 * 직렬로 들어온 요청만 트랜잭션 밖에서 409 · 첫 줄로 답한다. 동시 요청이나 옛 스냅샷 경로는 예외로 롤백되니 다시 부르면 409 · 첫 줄을 받는다.
 * 메시지에는 키를 싣지 않는다(`INSUFFICIENT` 처럼 일반 문구).
 */
class PointSourceConflictException :
    ApplicationException("Point source key was already used for a different request", PointErrorCode.SOURCE_CONFLICT)

/** 되돌릴 차감이 없다 - 그 키의 줄이 없거나, 차감(`SPEND`)이 아니거나, 환원 기록이 생기기 전의 옛 차감이다. 아무것도 바뀌지 않는다 */
class PointSpendNotFoundException : ApplicationException("No such deduction to cancel", PointErrorCode.SPEND_NOT_FOUND)

/** 취소 합계가 원 차감을 넘는다(또는 이미 전부 되돌렸다) - 아무것도 바뀌지 않는다 */
class PointCancelExceededException(requested: Long, cancellable: Long) :
    ApplicationException("Cancellation exceeds the deduction: requested $requested, cancellable $cancellable", PointErrorCode.CANCEL_EXCEEDED)
