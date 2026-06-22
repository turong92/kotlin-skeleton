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
import org.springframework.web.servlet.resource.NoResourceFoundException

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
            apiError(
                errorCode = PlatformErrorCode.VALIDATION_FAILED,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
                errors = fieldErrors,
            ),
        )
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleParamValidation(ex: HandlerMethodValidationException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            apiError(
                errorCode = PlatformErrorCode.PARAMETER_VALIDATION_FAILED,
                detail = ex.message,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
            ),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedJson(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            apiError(
                errorCode = PlatformErrorCode.MALFORMED_REQUEST,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
            ),
        )

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(ex: NoResourceFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            apiError(
                errorCode = PlatformErrorCode.NOT_FOUND,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
            ),
        )

    @ExceptionHandler(ApplicationException::class)
    fun handleApplication(ex: ApplicationException): ResponseEntity<ApiError> {
        if (ex.status.is5xxServerError) {
            log.error("Application exception: {}", ex.message, ex)
        } else {
            log.warn("Application exception: {}", ex.message)
        }
        return ResponseEntity.status(ex.status).body(
            apiError(
                errorCode = ex.errorCode,
                detail = ex.detail,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
                data = ex.data,
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(ex: Exception): ResponseEntity<ApiError> {
        if (ex.isDataIntegrityViolation()) {
            log.warn("Data integrity violation", ex)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                apiError(
                    errorCode = PlatformErrorCode.DATA_INTEGRITY_VIOLATION,
                    traceId = currentTraceId(),
                    spanId = currentSpanId(),
                ),
            )
        }

        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            apiError(
                errorCode = PlatformErrorCode.INTERNAL_SERVER_ERROR,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
            ),
        )
    }

    private fun apiError(
        errorCode: ErrorCode,
        traceId: String?,
        spanId: String?,
        detail: String? = errorCode.defaultDetail,
        errors: List<ApiError.FieldError>? = null,
        data: Any? = null,
    ): ApiError =
        ApiError(
            code = errorCode.code,
            title = errorCode.title,
            status = errorCode.status.value(),
            detail = detail ?: errorCode.defaultDetail,
            traceId = traceId,
            spanId = spanId,
            errors = errors,
            data = data,
        )

    private fun currentTraceId(): String? = MDC.get(TraceIdFilter.MDC_KEY)
    private fun currentSpanId(): String? = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY)

    private fun Throwable.isDataIntegrityViolation(): Boolean =
        generateSequence(this as Throwable?) { it.cause }
            .any { it.javaClass.name == "org.springframework.dao.DataIntegrityViolationException" }
}
