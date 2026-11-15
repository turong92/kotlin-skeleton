package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.RequestPaths
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.web.cors.CorsUtils
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * 점검 중이면 통과 경로([passPaths]) 밖의 요청을 503 + `Retry-After` + 안내 문구(`APP_CONTROL.MAINTENANCE`)로 막는다. 보안 체인보다 앞에 서므로 로그인 전 요청도 막힌다.
 * `Retry-After` 는 점검 끝 예정이 있으면 그때까지 남은 초(올림), 없으면 [fallbackRetryAfter]. 경로는 스프링이 핸들러를 고르는 방식 그대로 읽는다([RequestPaths]).
 * 점검 상태는 [AppControl] 의 캐시를 읽는다(요청마다 쿼리 없음). CORS preflight 는 막지 않는다 - 브라우저가 503 본문을 읽으려면 preflight 가 먼저 통과해야 한다.
 */
class MaintenanceFilter(
    private val control: AppControl,
    private val time: TimeProvider,
    private val passPaths: List<String>,
    private val fallbackRetryAfter: Duration,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val patterns = passPaths.map { RequestPaths.pattern(it) }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        // 통과 경로는 상태를 읽지도 않는다 - 프로젝트 없이 오는 헬스체크가 DB 를 프로젝트 문맥 없이 쓰지 않게
        if (CorsUtils.isPreFlightRequest(request) || patterns.any { RequestPaths.matches(it, request) }) {
            filterChain.doFilter(request, response)
            return
        }
        val maintenance = control.snapshot().maintenance
        if (!maintenance.enabled) {
            filterChain.doFilter(request, response)
            return
        }
        val retryAfter = maintenance.endsAt?.let { Duration.between(time.now(), it) }?.takeIf { !it.isNegative && !it.isZero } ?: fallbackRetryAfter
        response.status = AppControlErrorCode.MAINTENANCE.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader("Retry-After", retryAfter.seconds.plus(if (retryAfter.nano > 0) 1 else 0).coerceAtLeast(1).toString())
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                code = AppControlErrorCode.MAINTENANCE.code,
                title = AppControlErrorCode.MAINTENANCE.title,
                status = AppControlErrorCode.MAINTENANCE.status.value(),
                detail = maintenance.message ?: DEFAULT_DETAIL,
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )
    }

    private companion object {
        const val DEFAULT_DETAIL = "The service is under maintenance. Please try again later."
    }
}
