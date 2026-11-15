package dev.sumin.skeleton.routing.web

import dev.sumin.skeleton.common.authz.OperatorRef
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * 지금 요청의 인증된 호출자. spring-security-core 를 참조하는 곳을 이 객체 하나로 모았다 —
 * 시큐리티가 클래스패스에 없으면 호출하지 않으므로(필터가 [ClassUtils] 로 먼저 본다) 클래스도 올라오지 않는다.
 */
internal object SecurityCaller {
    private const val ROLE_PREFIX = "ROLE_"

    fun current(): OperatorRef? {
        val auth = SecurityContextHolder.getContext().authentication ?: return null
        if (auth is AnonymousAuthenticationToken || !auth.isAuthenticated) return null
        return OperatorRef(auth.name, auth.authorities.mapNotNull { it.authority?.removePrefix(ROLE_PREFIX) }.toSet())
    }
}
