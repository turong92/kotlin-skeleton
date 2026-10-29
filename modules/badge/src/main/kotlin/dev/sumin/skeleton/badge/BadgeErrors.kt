package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 배지 에러 코드 — `BADGE.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class BadgeErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NOT_FOUND("BADGE.NOT_FOUND", HttpStatus.NOT_FOUND, "Badge not found"),
    ARCHIVED("BADGE.ARCHIVED", HttpStatus.CONFLICT, "Badge is archived and cannot be awarded"),
    CODE_TAKEN("BADGE.CODE_TAKEN", HttpStatus.CONFLICT, "Badge code is already taken"),
    CONFIG_INVALID("BADGE.CONFIG_INVALID", HttpStatus.BAD_REQUEST, "Badge input is invalid"),
}

class BadgeException(val badgeError: BadgeErrorCode, message: String) : ApplicationException(message, badgeError)
