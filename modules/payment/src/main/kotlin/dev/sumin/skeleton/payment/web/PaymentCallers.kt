package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.payment.PaymentOperatorPolicy
import dev.sumin.skeleton.payment.PaymentPermissions
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.method.HandlerMethod

/** `Authentication` → 호출자. 계정 id 는 `Authentication.name`(auth 모듈에서는 계정 id). 운영자 경로는 매 호출 [PaymentOperatorPolicy] 에 핸들러의 권한([OperatorPermission])을 물어 확인한다(거절은 403). */
class PaymentCallers(private val policy: PaymentOperatorPolicy?) {
    fun account(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }

    /** 운영자의 id(이력 · 환불 줄에 남는다). 핸들러의 권한을 정책이 허락하지 않거나 핸들러가 권한을 말하지 않으면 403. */
    fun operator(authentication: Authentication?, handler: HandlerMethod): String {
        val id = account(authentication)
        val permission = handler.getMethodAnnotation(OperatorPermission::class.java)?.value
        if (permission == null || policy == null || !policy.permits(authentication!!, permission)) {
            throw ApplicationException("Missing permission: ${permission ?: "(none declared)"}", PlatformErrorCode.FORBIDDEN)
        }
        return id
    }
}

/** 기본 운영자 정책 — platform 의 [OperatorAccess] 가 권한별로 판정한다(`account` 가 있으면 매 호출 저장소를 읽으므로 회수가 바로 먹는다). */
class OperatorAccessPaymentOperatorPolicy(private val access: OperatorAccess) : PaymentOperatorPolicy {
    override fun isOperator(authentication: Authentication): Boolean = permits(authentication, PaymentPermissions.READ)

    override fun permits(authentication: Authentication, permission: String): Boolean {
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return access.has(OperatorRef(authentication.name, roles), permission)
    }
}
