package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.RateLimitKeyResolver
import jakarta.servlet.http.HttpServletRequest

/** 인증된 호출자는 계정으로, 익명은 [ClientIps] 가 푼 클라이언트 IP(`skeleton.web.client-ip.mode`)로 센다 — `remoteAddr` 를 직접 보지 않는다 */
class PrincipalAwareRateLimitKeyResolver(
    private val clientIps: ClientIps = ClientIps(),
) : RateLimitKeyResolver {
    override fun resolve(request: HttpServletRequest): String {
        val principalName = request.userPrincipal?.name
            ?.takeIf { it.isNotBlank() }
            ?: request.remoteUser?.takeIf { it.isNotBlank() }

        return when {
            principalName != null -> "principal:$principalName"
            else -> "ip:${clientIps.of(request).limitKey}"
        }
    }
}
