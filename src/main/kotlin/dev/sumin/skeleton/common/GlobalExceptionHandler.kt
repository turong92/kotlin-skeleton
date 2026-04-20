package dev.sumin.skeleton.common

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

/**
 * 애플리케이션 전역 예외 → [ApiError] 표준 응답으로 변환.
 *
 * - [TraceIdFilter]가 MDC에 심어둔 traceId를 꺼내 응답 body에 포함
 * - 프론트 토스트/콘솔에 이 traceId가 찍히면, 서버 로그에서 그 traceId로 전체 흐름 추적 가능
 * - 도메인 전용 예외는 [ApplicationException] 상속으로 선언 후 각자 HTTP 상태 매핑
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val fieldErrors = ex.bindingResult.fieldErrors.map {
            ApiError.FieldError(
                field = it.field,
                code = it.code ?: "INVALID",
                message = it.defaultMessage,
            )
        }
        return ResponseEntity.badRequest().body(
            ApiError(
                title = "Validation failed",
                status = HttpStatus.BAD_REQUEST.value(),
                detail = "Request body validation failed",
                traceId = currentTraceId(),
                errors = fieldErrors,
            ),
        )
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleParamValidation(ex: HandlerMethodValidationException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError(
                title = "Parameter validation failed",
                status = HttpStatus.BAD_REQUEST.value(),
                detail = ex.message,
                traceId = currentTraceId(),
            ),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedJson(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError(
                title = "Malformed request body",
                status = HttpStatus.BAD_REQUEST.value(),
                detail = ex.mostSpecificCause.message,
                traceId = currentTraceId(),
            ),
        )

    @ExceptionHandler(ApplicationException::class)
    fun handleApplication(ex: ApplicationException): ResponseEntity<ApiError> {
        log.warn("Application exception: {}", ex.message)
        return ResponseEntity.status(ex.status).body(
            ApiError(
                title = ex.title,
                status = ex.status.value(),
                detail = ex.message,
                traceId = currentTraceId(),
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(ex: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError(
                title = "Internal server error",
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                detail = "An unexpected error occurred. Use traceId for investigation.",
                traceId = currentTraceId(),
            ),
        )
    }

    private fun currentTraceId(): String? = MDC.get(TraceIdFilter.MDC_KEY)
}
