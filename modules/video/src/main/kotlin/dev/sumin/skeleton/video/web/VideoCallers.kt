package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 호출자를 푸는 한 곳 — Spring Security 가 있을 때만 읽히는 클래스다 */
internal object VideoCallers {
    /** 익명이면 401. 계정 id 는 `Authentication.name` */
    fun account(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }

    /** 로그인한 운영자만 — 로그인하지 않았으면 401, 운영자가 아니면 403. 계정 id 를 돌려준다 */
    fun operator(authentication: Authentication?, policy: VideoOperatorPolicy): String {
        val account = account(authentication)
        if (!policy.isOperator(authentication!!)) throw ApplicationException("operator only", PlatformErrorCode.FORBIDDEN)
        return account
    }
}
