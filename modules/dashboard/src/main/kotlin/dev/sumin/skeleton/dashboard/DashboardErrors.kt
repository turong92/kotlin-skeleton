package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 대시보드 에러 코드 — `DASHBOARD.` 로 시작한다 (프론트는 이 문자열로 분기한다). 권한 부족 403 은 `COMMON.FORBIDDEN` */
enum class DashboardErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    RANGE_INVALID("DASHBOARD.RANGE_INVALID", HttpStatus.BAD_REQUEST, "Invalid date range"),
    RANKING_NOT_FOUND("DASHBOARD.RANKING_NOT_FOUND", HttpStatus.NOT_FOUND, "Ranking not found"),
    LAYOUT_UNAVAILABLE("DASHBOARD.LAYOUT_UNAVAILABLE", HttpStatus.NOT_FOUND, "Screen settings are not stored on the server"),
    LAYOUT_INVALID("DASHBOARD.LAYOUT_INVALID", HttpStatus.BAD_REQUEST, "Invalid screen settings"),
}

class DashboardException(errorCode: DashboardErrorCode, message: String) : ApplicationException(message, errorCode)
