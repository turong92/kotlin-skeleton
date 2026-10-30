package dev.sumin.skeleton.review.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorRef
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

internal object OperatorRefs {
    /** 익명이면 null */
    fun of(authentication: Authentication?): OperatorRef? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        return OperatorRef(authentication.name, authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet())
    }

    fun require(authentication: Authentication?): OperatorRef =
        of(authentication) ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
