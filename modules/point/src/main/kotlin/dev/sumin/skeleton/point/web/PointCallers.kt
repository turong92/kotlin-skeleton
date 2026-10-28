package dev.sumin.skeleton.point.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorRef
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 포인트 경로를 여는 컨트롤러가 같은 방식으로 호출자를 푼다. Spring Security 가 있을 때만 읽히는 클래스다 */
internal object PointCallers {
    /** 익명이면 401. 계정 id 는 `Authentication.name` */
    fun account(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }

    /** `Authentication` → [OperatorRef]. 토큰 역할은 `ROLE_` 를 뗀 권한 이름. 익명이면 null (→ 401) */
    fun operatorOf(authentication: Authentication?): OperatorRef? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        return OperatorRef(authentication.name, authentication.authorities.mapTo(HashSet()) { it.authority.orEmpty().removePrefix("ROLE_") })
    }
}
