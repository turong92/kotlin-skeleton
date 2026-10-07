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
    EMAIL_NOT_VERIFIED(
        code = "AUTH.EMAIL_NOT_VERIFIED",
        status = HttpStatus.FORBIDDEN,
        title = "Email not verified",
        defaultDetail = "Verify your email address before signing in",
    ),
    ACCOUNT_SUSPENDED(
        code = "AUTH.ACCOUNT_SUSPENDED",
        status = HttpStatus.FORBIDDEN,
        title = "Account suspended",
        defaultDetail = "This account is suspended",
    ),
    ACCOUNT_DELETION_PENDING(
        code = "AUTH.ACCOUNT_DELETION_PENDING",
        status = HttpStatus.FORBIDDEN,
        title = "Account deletion pending",
        defaultDetail = "This account is scheduled for deletion. It can be restored until the deletion date",
    ),
    TOO_MANY_ATTEMPTS(
        code = "AUTH.TOO_MANY_ATTEMPTS",
        status = HttpStatus.TOO_MANY_REQUESTS,
        title = "Too many sign-in attempts",
        defaultDetail = "Too many sign-in attempts. Try again later",
    ),
}
