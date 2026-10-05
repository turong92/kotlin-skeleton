package dev.sumin.skeleton.idempotency

import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest

fun interface IdempotencyScopeResolver {
    fun resolve(request: HttpServletRequest): String
}

/** 인증된 호출자는 계정 이름, 익명은 [ClientIps] 가 푼 클라이언트 IP(`skeleton.web.client-ip.mode`) — `remoteAddr` 를 직접 보지 않는다 */
class PrincipalIdempotencyScopeResolver(
    private val clientIps: ClientIps = ClientIps(),
) : IdempotencyScopeResolver {
    override fun resolve(request: HttpServletRequest): String =
        request.userPrincipal?.name
            ?.takeIf { it.isNotBlank() }
            ?: "anonymous:${clientIps.of(request).limitKey}"
}
