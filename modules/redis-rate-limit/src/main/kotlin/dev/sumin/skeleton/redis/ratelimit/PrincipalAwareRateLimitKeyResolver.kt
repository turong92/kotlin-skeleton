package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.common.web.RateLimitKeyResolver
import jakarta.servlet.http.HttpServletRequest

class PrincipalAwareRateLimitKeyResolver : RateLimitKeyResolver {
    override fun resolve(request: HttpServletRequest): String {
        val principalName = request.userPrincipal?.name
            ?.takeIf { it.isNotBlank() }
            ?: request.remoteUser?.takeIf { it.isNotBlank() }

        return when {
            principalName != null -> "principal:$principalName"
            else -> "ip:${request.remoteAddr ?: "unknown"}"
        }
    }
}
