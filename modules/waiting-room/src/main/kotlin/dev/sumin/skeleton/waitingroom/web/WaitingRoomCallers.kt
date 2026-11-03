package dev.sumin.skeleton.waitingroom.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 호출자를 푸는 한 곳 — Spring Security 가 있을 때만 읽히는 클래스다 */
internal object WaitingRoomCallers {
    /** 익명이면 401. 계정 id 는 `Authentication.name` */
    fun account(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }
}
