package dev.sumin.skeleton.common

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
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

    /** `?status=NOPE` · `?page=abc` 처럼 값을 타입으로 바꿀 수 없는 요청 — 클라이언트 잘못이라 500 이 아니라 400 이다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            apiError(
                errorCode = PlatformErrorCode.PARAMETER_VALIDATION_FAILED,
                detail = "Parameter '${ex.name}' has an invalid value.",
                traceId = currentTraceId(),
                spanId = currentSpanId(),
                errors = listOf(ApiError.FieldError(field = ex.name, code = "TypeMismatch", message = "Invalid value")),
            ),
        )

    /** 필수 쿼리 파라미터가 아예 빠진 경우 — 값이 잘못된 경우와 같은 코드로 답하고 파라미터 이름을 알려 준다 */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(ex: MissingServletRequestParameterException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            apiError(
                errorCode = PlatformErrorCode.PARAMETER_VALIDATION_FAILED,
                detail = "Parameter '${ex.parameterName}' is required.",
                traceId = currentTraceId(),
                spanId = currentSpanId(),
                errors = listOf(ApiError.FieldError(field = ex.parameterName, code = "Missing", message = "Required")),
            ),
        )

    /** 없는 메서드 — 공개 주소에 GET 한 번으로 ERROR 로그 · 500 이 나지 않게. 허용 메서드는 `Allow` 헤더로 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ApiError> {
        val response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        ex.supportedHttpMethods?.takeIf { it.isNotEmpty() }?.let { response.allow(*it.toTypedArray()) }
        return response.body(
            apiError(
                errorCode = PlatformErrorCode.METHOD_NOT_ALLOWED,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
            ),
        )
    }

    /** `consumes` 가 받지 않는 본문 형식 — 클라이언트 잘못이라 500 이 아니라 415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(ex: HttpMediaTypeNotSupportedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
            apiError(
                errorCode = PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
                traceId = currentTraceId(),
                spanId = currentSpanId(),
            ),
        )

    /** 클라이언트가 받을 수 있는 형식을 못 준다 — JSON 본문을 쓰면 그것도 못 받으니 본문 없이 406 */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptable(ex: HttpMediaTypeNotAcceptableException): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build()

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
