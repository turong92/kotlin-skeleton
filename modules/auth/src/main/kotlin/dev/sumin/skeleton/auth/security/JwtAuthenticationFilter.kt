package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.web.BearerIgnoredPaths
import dev.sumin.skeleton.common.web.RequestPaths
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtTokenService: JwtTokenService,
    private val authErrorWriter: AuthErrorWriter,
    /** 자기 인증을 따로 하는 경로 — Bearer 를 보지 않고 지나보낸다 (예: 작업 큐 깨우기 주소가 Scheduler 의 OIDC 토큰을 받는 경우) */
    private val bearerIgnored: List<BearerIgnoredPaths> = emptyList(),
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val existingAuthentication = SecurityContextHolder.getContext().authentication
        if (existingAuthentication?.isAuthenticated == true) {
            filterChain.doFilter(request, response)
            return
        }

        if (bearerIgnored.isNotEmpty() && bearerIgnored.flatMap { it.paths() }.any { RequestPaths.matches(RequestPaths.pattern(it), request) }) {
            filterChain.doFilter(request, response)
            return
        }

        val authorization = request.getHeader("Authorization")
        val token = authorization?.bearerToken()

        if (authorization != null && token == null) {
            authErrorWriter.writeUnauthorized(response, "Invalid bearer token")
            return
        }

        if (token != null) {
            // 프로젝트 모드에서 프로젝트 없는 경로(어느 프로젝트의 호스트도 아님)에 온 토큰은 어느 프로젝트의 키로도 검증할 수 없다 — 인증 실패(401)이지 500 이 아니다.
            // (서비스 자체는 프로젝트 문맥 없이 부르면 일부러 예외를 던진다 — 조용히 하나를 고르지 않는다)
            val principal = try { jwtTokenService.authenticate(token) } catch (_: NoProjectContextException) { null }
            if (principal == null) {
                authErrorWriter.writeUnauthorized(response, "Invalid bearer token")
                return
            }

            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = AuthPrincipalAuthentication(principal)
            SecurityContextHolder.setContext(context)
        }

        filterChain.doFilter(request, response)
    }

    private fun String.bearerToken(): String? {
        val trimmed = trim()
        if (!trimmed.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return trimmed.substring(7).trim().takeIf { it.isNotEmpty() }
    }
}
