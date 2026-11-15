package dev.sumin.skeleton.routing.web

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.routing.RoutingErrorCode
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

/** 필터는 컨트롤러 어드바이스 앞에 서므로 [ApiError] 를 직접 쓴다 — 표시 값 · 주소는 싣지 않는다 */
class RoutingErrorWriter(private val objectMapper: ObjectMapper) {
    fun write(response: HttpServletResponse, error: RoutingErrorCode, detail: String? = null) {
        response.status = error.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                code = error.code,
                title = error.title,
                status = error.status.value(),
                detail = detail,
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )
    }
}
