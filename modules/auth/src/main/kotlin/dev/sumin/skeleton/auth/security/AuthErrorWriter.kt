package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.TraceIdFilter
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

    fun writeForbidden(response: HttpServletResponse, detail: String? = null) {
        write(response, PlatformErrorCode.FORBIDDEN, detail)
    }

    private fun write(
        response: HttpServletResponse,
        errorCode: PlatformErrorCode,
        detail: String?,
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
            ),
        )
    }
}
