package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.workspace.CurrentWorkspace
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** 응답이 요청 헤더 `X-Workspace-Id` 에 따라 달라질 수 있다고 캐시에 알린다(`Vary`) - 다른 팀의 응답이 섞여 나가지 않게 */
class WorkspaceVaryFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.addHeader("Vary", CurrentWorkspace.HEADER)
        chain.doFilter(request, response)
    }
}
