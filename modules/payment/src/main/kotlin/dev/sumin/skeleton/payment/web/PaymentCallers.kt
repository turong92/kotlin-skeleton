package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.payment.PaymentOperatorPolicy
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** `Authentication` → 호출자. 계정 id 는 `Authentication.name`(auth 모듈에서는 계정 id). 운영자 경로는 매 호출 [PaymentOperatorPolicy] 를 확인한다(거절은 403). */
class PaymentCallers(private val policy: PaymentOperatorPolicy?) {
    fun account(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }

    /** 운영자의 id(이력 · 환불 줄에 남는다). 정책이 거절하면 403. */
    fun operator(authentication: Authentication?): String {
        val id = account(authentication)
        if (policy == null || !policy.isOperator(authentication!!)) throw ApplicationException("Operator role required", PlatformErrorCode.FORBIDDEN)
        return id
    }
}

/** 기본 운영자 정책 — 토큰에 `ROLE_<role>`(또는 `<role>`) 권한이 있다. 토큰이 낡았을 수 있다(≤15분) — 계정 모듈을 쓰는 앱은 매 호출 저장소의 역할을 읽는 빈으로 갈아 끼운다. */
class RolePaymentOperatorPolicy(private val role: String) : PaymentOperatorPolicy {
    override fun isOperator(authentication: Authentication): Boolean =
        authentication.authorities.any { it.authority == "ROLE_$role" || it.authority == role }
}
