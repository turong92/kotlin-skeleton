package dev.sumin.skeleton.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

class SecurityHeadersFilter(
    private val properties: WebProperties.SecurityHeaders,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Permissions-Policy", DEFAULT_PERMISSIONS_POLICY)
        properties.contentSecurityPolicy
            .takeIf { it.isNotBlank() }
            ?.let { response.setHeader("Content-Security-Policy", it) }

        if (request.isSecure && properties.hsts.enabled) {
            response.setHeader("Strict-Transport-Security", hstsHeader())
        }

        filterChain.doFilter(request, response)
    }

    private fun hstsHeader(): String =
        buildString {
            append("max-age=")
            append(properties.hsts.maxAge)
            if (properties.hsts.includeSubDomains) {
                append("; includeSubDomains")
            }
        }

    private companion object {
        const val DEFAULT_PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"
    }
}
