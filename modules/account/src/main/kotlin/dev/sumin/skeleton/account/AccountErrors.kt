package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 계정 에러 코드 — `ACCOUNT.` 로 시작한다 (프론트는 이 문자열로 분기한다). 로그인 쪽 코드는 `AUTH.*` ([dev.sumin.skeleton.auth.api.AuthErrorCode]) */
enum class AccountErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    TOKEN_INVALID("ACCOUNT.TOKEN_INVALID", HttpStatus.GONE, "Link is invalid or expired"),
    PASSWORD_POLICY("ACCOUNT.PASSWORD_POLICY", HttpStatus.BAD_REQUEST, "Password does not meet the policy"),
    CURRENT_PASSWORD_INVALID("ACCOUNT.CURRENT_PASSWORD_INVALID", HttpStatus.BAD_REQUEST, "Current password is wrong"),
    REAUTH_REQUIRED("ACCOUNT.REAUTH_REQUIRED", HttpStatus.FORBIDDEN, "Confirm by email first"),
    REAUTH_FAILED("ACCOUNT.REAUTH_FAILED", HttpStatus.BAD_REQUEST, "Confirmation failed"),
    CAPTCHA_FAILED("ACCOUNT.CAPTCHA_FAILED", HttpStatus.BAD_REQUEST, "Captcha verification failed"),
    EMAIL_TAKEN("ACCOUNT.EMAIL_TAKEN", HttpStatus.CONFLICT, "Email already in use"),
    SIGN_UP_CLOSED("ACCOUNT.SIGN_UP_CLOSED", HttpStatus.FORBIDDEN, "Sign-up is closed"),
    SOCIAL_EMAIL_CONFLICT("ACCOUNT.SOCIAL_EMAIL_CONFLICT", HttpStatus.CONFLICT, "An account with this email already exists"),
    IDENTITY_TAKEN("ACCOUNT.IDENTITY_TAKEN", HttpStatus.CONFLICT, "This sign-in method belongs to another account"),
    IDENTITY_EXISTS("ACCOUNT.IDENTITY_EXISTS", HttpStatus.CONFLICT, "This sign-in method is already linked"),
    IDENTITY_NOT_FOUND("ACCOUNT.IDENTITY_NOT_FOUND", HttpStatus.NOT_FOUND, "Sign-in method not found"),
    LAST_SIGN_IN_METHOD("ACCOUNT.LAST_SIGN_IN_METHOD", HttpStatus.CONFLICT, "Cannot remove the last sign-in method"),
    LAST_ADMIN("ACCOUNT.LAST_ADMIN", HttpStatus.CONFLICT, "Cannot remove the last administrator"),
    SELF_ACTION_FORBIDDEN("ACCOUNT.SELF_ACTION_FORBIDDEN", HttpStatus.CONFLICT, "You cannot do this to your own account"),
    NOT_FOUND("ACCOUNT.NOT_FOUND", HttpStatus.NOT_FOUND, "Account not found"),
    SUSPENDED_CANNOT_DELETE("ACCOUNT.SUSPENDED_CANNOT_DELETE", HttpStatus.FORBIDDEN, "A suspended account cannot be deleted by its owner"),
    REGISTRATION_BLOCKED("ACCOUNT.REGISTRATION_BLOCKED", HttpStatus.FORBIDDEN, "This email or sign-in account cannot be used to register"),
    NOT_SUSPENDED("ACCOUNT.NOT_SUSPENDED", HttpStatus.CONFLICT, "Only a suspended account can be erased by an administrator"),
    ERASED("ACCOUNT.ERASED", HttpStatus.GONE, "The account was erased and cannot be used or restored"),
    RATE_LIMITED("ACCOUNT.RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    METHOD_UNKNOWN("ACCOUNT.METHOD_UNKNOWN", HttpStatus.BAD_REQUEST, "Unknown sign-in method"),
    CODE_INVALID("ACCOUNT.CODE_INVALID", HttpStatus.BAD_REQUEST, "The code is wrong"),
    CODE_EXPIRED("ACCOUNT.CODE_EXPIRED", HttpStatus.GONE, "The code is expired or used up"),
    PASSWORD_REQUIRED("ACCOUNT.PASSWORD_REQUIRED", HttpStatus.BAD_REQUEST, "Verify your email before setting a password"),
}

class AccountException(
    errorCode: AccountErrorCode,
    message: String = errorCode.title,
    data: Any? = null,
) : ApplicationException(message = message, errorCode = errorCode, data = data)

/** 코드가 틀렸다 — 남은 시도가 `data.attemptsLeft` 로 나간다 */
class CodeInvalidException(attemptsLeft: Int) : ApplicationException(
    message = AccountErrorCode.CODE_INVALID.title, errorCode = AccountErrorCode.CODE_INVALID, data = mapOf("attemptsLeft" to attemptsLeft),
)

/** 정책 위반 코드 — HTTP 로는 `data.violations` 로 나간다 (프론트가 문구를 고른다) */
enum class PasswordViolation { TOO_SHORT, TOO_LONG, NEEDS_LETTER, NEEDS_DIGIT, NEEDS_SYMBOL, CONTAINS_EMAIL, TOO_COMMON, BREACHED }

class PasswordPolicyException(val violations: List<PasswordViolation>) :
    ApplicationException(
        message = "Password does not meet the policy: $violations",
        errorCode = AccountErrorCode.PASSWORD_POLICY,
        data = mapOf("violations" to violations.map { it.name }),
    )
