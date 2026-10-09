package dev.sumin.skeleton.payment.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 운영자 경로의 접근 규칙이 걸리는 **한 곳** — [PaymentAdminController] 의 모든 핸들러는 본문 · 쿼리를 읽기 전에 여기서 막힌다
 * (익명 `401` · 정책 거절 `403`; 그래서 타입이 틀린 쿼리로 역할 없는 호출자가 `400` 을 받는 일도 없다). 핸들러가 손으로 검사를 부르지 않으므로 새 핸들러가 빠뜨릴 수 없다.
 * 판정은 **하위 타입까지** 본다(앱이 컨트롤러를 상속한 빈으로 갈아 끼워도 걸린다). 이 인터셉터가 안 걸리는 조립(컨트롤러 빈만 직접 등록 · `WebMvcConfigurer` 를 읽지 않는 설정)에서는 컨트롤러 쪽 [PaymentAdminController.requireOperator] 가 모든 핸들러를 닫는다 — 두 겹이다.
 * 통과하면 운영자 id 를 요청 속성 [OPERATOR_ATTRIBUTE] 에 싣는다 — 이력 · 환불 줄에 남길 값이 필요한 핸들러만 `@RequestAttribute` 로 받는다(속성이 없으면 요청이 거절된다).
 */
class PaymentOperatorInterceptor(private val callers: PaymentCallers) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler is HandlerMethod && PaymentAdminController::class.java.isAssignableFrom(handler.beanType)) {
            request.setAttribute(OPERATOR_ATTRIBUTE, callers.operator(request.userPrincipal as? Authentication))
        }
        return true
    }

    companion object {
        const val OPERATOR_ATTRIBUTE = "dev.sumin.skeleton.payment.operatorId"
    }
}
