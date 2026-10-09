package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.account.web.AccountCallers
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.payment.PaymentOperatorPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 이 앱이 결제 장부에 끼우는 운영자 판정(스펙 §4.2). 장부의 기본은 **토큰의 역할**인데 토큰은 낡았을 수 있다(≤15분) — 계정 모듈을 쓰는 이 앱은 운영자 계정 도구가 쓰는
 * `AccountCallers.requireAdmin` 으로 갈아 끼운다: **매 호출 저장소의 계정을 다시 읽어** 정지되었거나 ADMIN 을 회수당한 계정은 토큰이 아직 유효해도 403 이다.
 * 역할 이름은 `skeleton.account.admin.role`(기본 ADMIN) — `skeleton.payment.admin.role` 은 이 빈이 있으면 쓰이지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class CheerPaymentConfiguration {
    @Bean
    fun paymentOperatorPolicy(callers: AccountCallers): PaymentOperatorPolicy =
        PaymentOperatorPolicy { authentication ->
            try {
                callers.requireAdmin(authentication)
                true
            } catch (_: ApplicationException) {
                false   // 로그인하지 않았거나(401) 관리자가 아니다(403) — 호출한 쪽(PaymentCallers)이 403 으로 답한다
            }
        }
}
