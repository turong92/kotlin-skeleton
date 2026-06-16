package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

enum class AuthErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    INVALID_CREDENTIALS(
        code = "AUTH.INVALID_CREDENTIALS",
        status = HttpStatus.UNAUTHORIZED,
        title = "Invalid credentials",
        defaultDetail = "Invalid credentials",
    ),
}
