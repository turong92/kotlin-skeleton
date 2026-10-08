package dev.sumin.skeleton.common.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer
import org.springframework.http.server.RequestPath
import org.springframework.http.server.RequestPath.parse
import org.springframework.web.util.ServletRequestPathUtils
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * 경로 문자열로 요청을 가르는 모든 곳(필터 · 인터셉터)이 **스프링이 핸들러를 고르는 것과 같은 방식**으로 경로를 읽게 하는 한 곳.
 * `request.requestURI` 는 디코드 전이다 — `/%61pi/v1/x` 는 라우터에게는 `/api/v1/x` 인데 문자열 비교로는 "/api 로 시작하는 경로" 규칙에 걸리지 않아 규칙을 통째로 우회한다.
 * 여기서는 [PathPattern] 으로 세그먼트 단위 · 디코드 후(`;` 경로 매개변수 제외)로 맞춘다. 디스패처가 이미 푼 경로가 있으면 그것을, 없으면(서블릿 필터) 같은 방식으로 푼다.
 */
object RequestPaths {
    private val parser = PathPatternParser.defaultInstance

    fun pattern(pattern: String): PathPattern = parser.parse(pattern)

    /** 컨텍스트 경로를 뺀 요청 경로 (세그먼트는 [PathContainer] 가 디코드해 맞춘다) */
    fun withinApplication(request: HttpServletRequest): PathContainer {
        val parsed: RequestPath = if (ServletRequestPathUtils.hasParsedRequestPath(request)) ServletRequestPathUtils.getParsedRequestPath(request) else parse(request.requestURI, request.contextPath)
        return parsed.pathWithinApplication()
    }

    fun matches(pattern: PathPattern, request: HttpServletRequest): Boolean = pattern.matches(withinApplication(request))
}
