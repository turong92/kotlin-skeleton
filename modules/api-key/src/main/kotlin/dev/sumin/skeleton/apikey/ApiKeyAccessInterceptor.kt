package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.common.apikey.ApiKeyScope
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.RateLimitStore
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.cors.CorsUtils
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * **키로 온 요청**(인증이 [ApiKeyAuthentication])이 닿는 곳을 정한다 - 기본 거부:
 *  1. 키별 한도(켰을 때) - 넘으면 429 `API_KEY.RATE_LIMITED` + `Retry-After` + `X-RateLimit-*`. 키로 온 요청은 어떤 결과든 센다.
 *  2. 처리기에 [ApiKeyScope] 표시가 없으면 403 `API_KEY.NOT_ALLOWED` (정적 자원 · 함수형 라우트 같은 처리기도 같다)
 *  3. 키가 그 범위를 갖지 않으면 403 `API_KEY.SCOPE_MISSING` + `data.scope`
 * 사람의 토큰(JWT)으로 온 요청은 건드리지 않는다. 다른 문(이메일 필수 · 재동의 …)보다 먼저 돈다.
 */
class ApiKeyAccessInterceptor(
    private val properties: ApiKeyProperties,
    private val limits: () -> RateLimitStore,
    private val time: TimeProvider,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.dispatcherType == DispatcherType.ERROR || request.dispatcherType == DispatcherType.ASYNC) return true
        if (CorsUtils.isPreFlightRequest(request)) return true
        val key = SecurityContextHolder.getContext().authentication as? ApiKeyAuthentication ?: return true

        limit(key, response)
        val scope = (handler as? HandlerMethod)?.let { it.getMethodAnnotation(ApiKeyScope::class.java) ?: AnnotatedElementUtils.findMergedAnnotation(it.beanType, ApiKeyScope::class.java) }
            ?: throw ApiKeyException(ApiKeyErrorCode.NOT_ALLOWED)
        if (scope.value !in key.scopes) throw ApiKeyException(ApiKeyErrorCode.SCOPE_MISSING, data = mapOf("scope" to scope.value))
        return true
    }

    private fun limit(key: ApiKeyAuthentication, response: HttpServletResponse) {
        val config = properties.rateLimit
        if (config.capacity <= 0) return
        val now = time.now()
        val decision = limits().consume("api-key:${key.keyId}", config.capacity, config.window.toMillis(), now)
        response.setHeader("X-RateLimit-Limit", decision.limit.toString())
        response.setHeader("X-RateLimit-Remaining", decision.remaining.toString())
        response.setHeader("X-RateLimit-Reset", decision.resetAt.epochSecond.toString())
        if (decision.allowed) return
        val retryAfter = (decision.resetAt.epochSecond - now.epochSecond).coerceAtLeast(1)
        response.setHeader("Retry-After", retryAfter.toString())
        throw ApiKeyException(ApiKeyErrorCode.RATE_LIMITED, data = mapOf("retryAfterSeconds" to retryAfter))
    }
}
