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
}

/** 잔액보다 큰 차감(운영자 조정 포함) — 아무것도 바뀌지 않는다 */
class PointInsufficientException(needed: Long, available: Long) :
    ApplicationException("Not enough points: needed $needed, available $available", PointErrorCode.INSUFFICIENT)
