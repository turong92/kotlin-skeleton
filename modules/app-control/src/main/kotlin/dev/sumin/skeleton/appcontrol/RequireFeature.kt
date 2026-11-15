package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.common.ApplicationException
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 컨트롤러(클래스 또는 메서드)를 기능 스위치 [value] 뒤에 둔다 — 스위치가 꺼져 있으면 경로가 없는 것처럼 404 `APP_CONTROL.FEATURE_DISABLED`.
 * 운영자가 한 번도 만들지 않은 스위치는 꺼진 것으로 본다 — 평소엔 열려 있고 문제 때 끄는 경로는 [defaultEnabled] = true.
 * 메서드의 어노테이션이 클래스의 것보다 우선한다. 보안 체인 뒤에서 판정하므로 로그인이 필요한 경로는 익명에게 먼저 401 이다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequireFeature(val value: String, val defaultEnabled: Boolean = false)

class RequireFeatureInterceptor(private val flags: FeatureFlags) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod || request.dispatcherType == DispatcherType.ERROR || request.dispatcherType == DispatcherType.ASYNC) return true
        val gate = AnnotatedElementUtils.findMergedAnnotation(handler.method, RequireFeature::class.java)
            ?: AnnotatedElementUtils.findMergedAnnotation(handler.beanType, RequireFeature::class.java)
            ?: return true
        if (!flags.isEnabled(gate.value, gate.defaultEnabled)) {
            throw ApplicationException("Feature '${gate.value}' is off", AppControlErrorCode.FEATURE_DISABLED)
        }
        return true
    }
}
