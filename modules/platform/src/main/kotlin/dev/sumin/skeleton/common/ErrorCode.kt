package dev.sumin.skeleton.common

import org.springframework.http.HttpStatus

interface ErrorCode {
    val code: String
    val status: HttpStatus
    val title: String
    val defaultDetail: String?
        get() = null
}

enum class PlatformErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    VALIDATION_FAILED(
        code = "COMMON.VALIDATION_FAILED",
        status = HttpStatus.BAD_REQUEST,
        title = "Validation failed",
        defaultDetail = "Request body validation failed",
    ),
    PARAMETER_VALIDATION_FAILED(
        code = "COMMON.PARAMETER_VALIDATION_FAILED",
        status = HttpStatus.BAD_REQUEST,
        title = "Parameter validation failed",
    ),
    MALFORMED_REQUEST(
        code = "COMMON.MALFORMED_REQUEST",
        status = HttpStatus.BAD_REQUEST,
        title = "Malformed request body",
        defaultDetail = "Request body is malformed.",
    ),
    NOT_FOUND(
        code = "COMMON.NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "Not found",
        defaultDetail = "Requested resource was not found.",
    ),
    UNAUTHORIZED(
        code = "COMMON.UNAUTHORIZED",
        status = HttpStatus.UNAUTHORIZED,
        title = "Unauthorized",
    ),
    FORBIDDEN(
        code = "COMMON.FORBIDDEN",
        status = HttpStatus.FORBIDDEN,
        title = "Forbidden",
    ),
    TOO_MANY_REQUESTS(
        code = "COMMON.TOO_MANY_REQUESTS",
        status = HttpStatus.TOO_MANY_REQUESTS,
        title = "Too many requests",
        defaultDetail = "Rate limit exceeded",
    ),
    IDEMPOTENCY_ERROR(
        code = "COMMON.IDEMPOTENCY_ERROR",
        status = HttpStatus.BAD_REQUEST,
        title = "Invalid idempotency request",
    ),
    DATA_INTEGRITY_VIOLATION(
        code = "COMMON.DATA_INTEGRITY_VIOLATION",
        status = HttpStatus.CONFLICT,
        title = "Data integrity violation",
        defaultDetail = "Request conflicts with existing data.",
    ),
    EXTERNAL_SERVICE_ERROR(
        code = "COMMON.EXTERNAL_SERVICE_ERROR",
        status = HttpStatus.BAD_GATEWAY,
        title = "External service error",
    ),
    EXTERNAL_SERVICE_TIMEOUT(
        code = "COMMON.EXTERNAL_SERVICE_TIMEOUT",
        status = HttpStatus.GATEWAY_TIMEOUT,
        title = "External service timeout",
    ),
    INTERNAL_SERVER_ERROR(
        code = "COMMON.INTERNAL_SERVER_ERROR",
        status = HttpStatus.INTERNAL_SERVER_ERROR,
        title = "Internal server error",
        defaultDetail = "An unexpected error occurred. Use traceId for investigation.",
    ),
}

data class SimpleErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode
