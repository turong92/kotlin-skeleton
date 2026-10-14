package dev.sumin.skeleton.common.project

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.SimpleErrorCode
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.web.RequestPaths
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * 요청의 Host(`ForwardedHeaderFilter` 가 `X-Forwarded-Host` 를 푼 뒤)로 프로젝트를 정해 그 요청 동안 스레드 문맥에 둔다.
 * 목록에 없는 Host 는 421 로 거절한다. 단 컨테이너 헬스체크([UNSCOPED_PATHS])는 `localhost` 로 오므로 프로젝트 없이 지나간다 —
 * 그 경로들은 DB 를 프로젝트 문맥 없이 쓰지 않는다(헬스는 프로젝트별 풀을 각각 본다).
 */
class ProjectFilter(
    private val registry: ProjectRegistry,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val unscoped = UNSCOPED_PATHS.map(RequestPaths::pattern)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val project = registry.byHost(request.serverName)
        if (project != null && project.closed) {
            // 서비스를 끝낸 프로젝트 — 모르는 도메인(421)과 구분되는 503. DB 에는 붙지 않는다
            write(response, PROJECT_CLOSED)
            return
        }
        if (project != null) {
            ProjectContext.runIn(project) { chain.doFilter(request, response) }
            return
        }
        if (unscoped.any { RequestPaths.matches(it, request) }) {
            chain.doFilter(request, response)
            return
        }
        // 호스트 이름은 응답에 싣지 않는다 (남이 보낸 값)
        logger.debug("Refused a request for a host that belongs to no project")
        write(response, UNKNOWN_HOST)
    }

    private fun write(response: HttpServletResponse, error: SimpleErrorCode) {
        response.status = error.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                code = error.code, title = error.title, status = error.status.value(), detail = error.defaultDetail,
                traceId = MDC.get(TraceIdFilter.MDC_KEY), spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )
    }

    companion object {
        val UNKNOWN_HOST = SimpleErrorCode("COMMON.UNKNOWN_HOST", HttpStatus.MISDIRECTED_REQUEST, "Unknown host", "This host does not belong to any project of this server.")

        val PROJECT_CLOSED = SimpleErrorCode("COMMON.PROJECT_CLOSED", HttpStatus.SERVICE_UNAVAILABLE, "Service closed", "This service has ended.")

        /** 프로젝트 없이 지나가는 경로 — 액추에이터 `/health` · `/info` (배포 계약의 헬스체크) */
        val UNSCOPED_PATHS = listOf("/health", "/health/**", "/info")
    }
}
