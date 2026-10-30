package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.ErrorCode
import dev.sumin.skeleton.common.ForbiddenLog
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.TraceIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

class AuthErrorWriter(
    private val objectMapper: ObjectMapper,
) {
    fun writeUnauthorized(response: HttpServletResponse, detail: String? = null) {
        write(response, PlatformErrorCode.UNAUTHORIZED, detail)
    }

    /** 보안 체인이 권한으로 거절한 요청 — 403 을 쓰기 전에 `Forbidden:` 한 줄을 남긴다 */
    fun writeForbidden(request: HttpServletRequest, response: HttpServletResponse) {
        ForbiddenLog.warn(request, PlatformErrorCode.FORBIDDEN.code, "Access denied")
        writeForbidden(response)
    }

    fun writeForbidden(response: HttpServletResponse, detail: String? = null) {
        write(response, PlatformErrorCode.FORBIDDEN, detail)
    }

    /** [BearerTokenAuthenticator] 가 거절한 토큰 - 그 고리의 에러 코드 · 상태 · `data` 로 쓴다 */
    fun writeRejected(response: HttpServletResponse, rejected: BearerTokenRejectedException) {
        write(response, rejected.errorCode, rejected.message, rejected.data)
    }

    private fun write(
        response: HttpServletResponse,
        errorCode: ErrorCode,
        detail: String?,
        data: Any? = null,
    ) {
        if (response.isCommitted) return

        response.status = errorCode.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                code = errorCode.code,
                title = errorCode.title,
                status = errorCode.status.value(),
                detail = detail,
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
                data = data,
            ),
        )
    }
}
