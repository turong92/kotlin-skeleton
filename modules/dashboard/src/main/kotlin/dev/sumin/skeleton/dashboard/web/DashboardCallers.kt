package dev.sumin.skeleton.dashboard.web

import dev.sumin.skeleton.common.authz.OperatorRef
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 대시보드 경로를 여는 컨트롤러(이 모듈과 출처 모듈들)가 같은 방식으로 호출자를 푼다. Spring Security 가 있을 때만 읽히는 클래스다 */
object DashboardCallers {
    /** `Authentication` → [OperatorRef]. 호출자 id 는 `Authentication.name`, 토큰 역할은 `ROLE_` 를 뗀 권한 이름. 익명이면 null (→ 401) */
    fun operatorOf(authentication: Authentication?): OperatorRef? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        return OperatorRef(authentication.name, authentication.authorities.mapTo(HashSet()) { it.authority.orEmpty().removePrefix("ROLE_") })
    }
}
