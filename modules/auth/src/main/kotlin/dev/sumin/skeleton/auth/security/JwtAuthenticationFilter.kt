package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.jwt.JwtTokenService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtTokenService: JwtTokenService,
    private val authErrorWriter: AuthErrorWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorization = request.getHeader("Authorization")
        val token = authorization?.bearerToken()

        if (authorization != null && token == null) {
            authErrorWriter.writeUnauthorized(response, "Invalid bearer token")
            return
        }

        if (token != null) {
            val principal = jwtTokenService.authenticate(token)
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
