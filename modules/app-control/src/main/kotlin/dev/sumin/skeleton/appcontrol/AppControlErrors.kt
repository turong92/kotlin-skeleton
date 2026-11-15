package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 에러 코드 — `APP_CONTROL.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class AppControlErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    /** 꺼진 기능의 경로 — 경로가 없는 것처럼 404 */
    FEATURE_DISABLED("APP_CONTROL.FEATURE_DISABLED", HttpStatus.NOT_FOUND, "Feature disabled"),

    /** 점검 중 — `Retry-After` 와 함께 */
    MAINTENANCE("APP_CONTROL.MAINTENANCE", HttpStatus.SERVICE_UNAVAILABLE, "Under maintenance"),
}
