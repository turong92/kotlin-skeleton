package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 호출자의 계정 id 는 `Authentication.name` (auth 모듈에서는 계정 id) — 로그인하지 않았으면 401. 이 모듈의 경로는 공개 경로가 아니다 */
class AuthorProfileCallers {
    fun accountId(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }
}
