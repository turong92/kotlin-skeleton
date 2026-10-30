package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** API 키 에러 코드 - `API_KEY.` 로 시작한다 (프론트와 키를 쓰는 쪽은 이 문자열로 분기한다) */
enum class ApiKeyErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    INVALID("API_KEY.INVALID", HttpStatus.UNAUTHORIZED, "API key is invalid"),
    EXPIRED("API_KEY.EXPIRED", HttpStatus.UNAUTHORIZED, "API key has expired"),
    REVOKED("API_KEY.REVOKED", HttpStatus.UNAUTHORIZED, "API key was revoked"),
    OWNER_BLOCKED("API_KEY.OWNER_BLOCKED", HttpStatus.FORBIDDEN, "The owner of this API key cannot sign in"),
    NOT_ALLOWED("API_KEY.NOT_ALLOWED", HttpStatus.FORBIDDEN, "This endpoint cannot be called with an API key"),
    SCOPE_MISSING("API_KEY.SCOPE_MISSING", HttpStatus.FORBIDDEN, "The API key lacks the scope this endpoint needs"),
    RATE_LIMITED("API_KEY.RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many requests for this API key"),
    SCOPE_UNKNOWN("API_KEY.SCOPE_UNKNOWN", HttpStatus.BAD_REQUEST, "Unknown API key scope"),
    EXPIRES_INVALID("API_KEY.EXPIRES_INVALID", HttpStatus.BAD_REQUEST, "The expiry is missing, in the past, or beyond the allowed lifetime"),
    LIMIT_REACHED("API_KEY.LIMIT_REACHED", HttpStatus.CONFLICT, "Too many active API keys"),
    NOT_FOUND("API_KEY.NOT_FOUND", HttpStatus.NOT_FOUND, "API key not found"),
}

class ApiKeyException(val apiKeyError: ApiKeyErrorCode, message: String = apiKeyError.title, data: Any? = null) :
    ApplicationException(message = message, errorCode = apiKeyError, data = data)
