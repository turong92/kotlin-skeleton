package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/**
 * 작성자 프로필 에러 코드 — `AUTHOR_PROFILE.` 로 시작한다 (프론트는 이 문자열로 분기한다).
 * 이름 칸의 `400` 은 닉네임과 같은 코드(`Required` · `Size` · `Pattern` · `Reserved`)로 필드 `name` 에 나간다 ([dev.sumin.skeleton.common.FieldValidationException]).
 * [NOT_FOUND] 는 남의 · 없는 · 지워진 프로필을 **구별하지 않는다** — 같은 상태, 같은 문구.
 */
enum class AuthorProfileErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NAME_TAKEN("AUTHOR_PROFILE.NAME_TAKEN", HttpStatus.CONFLICT, "Name already in use"),
    LIMIT_REACHED("AUTHOR_PROFILE.LIMIT_REACHED", HttpStatus.CONFLICT, "Profile limit reached"),
    RATE_LIMITED("AUTHOR_PROFILE.RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    NOT_FOUND("AUTHOR_PROFILE.NOT_FOUND", HttpStatus.NOT_FOUND, "Profile not found"),
}

class AuthorProfileException(errorCode: AuthorProfileErrorCode, message: String = errorCode.title) : ApplicationException(message, errorCode)
