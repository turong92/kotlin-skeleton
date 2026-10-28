package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentExemption
import org.springframework.security.core.Authentication

/** 요청의 호출자가 결제 면제 계정인가 - 계정 id 는 `Authentication.name`, 토큰 역할은 `ROLE_` 를 뗀 권한 이름([OperatorAccessPaymentOperatorPolicy] 와 같은 풀이). */
fun PaymentExemption.isExempt(authentication: Authentication): Boolean =
    isExempt(authentication.name, authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet())
