package dev.sumin.skeleton.idempotency

import jakarta.servlet.http.HttpServletRequest

fun interface IdempotencyScopeResolver {
    fun resolve(request: HttpServletRequest): String
}

class PrincipalIdempotencyScopeResolver : IdempotencyScopeResolver {
    override fun resolve(request: HttpServletRequest): String =
        request.userPrincipal?.name
            ?.takeIf { it.isNotBlank() }
            ?: "anonymous:${request.remoteAddr ?: "unknown"}"
}
