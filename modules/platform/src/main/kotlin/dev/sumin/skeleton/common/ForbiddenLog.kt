package dev.sumin.skeleton.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory

/**
 * 권한 때문에 거절된(403) 요청을 로그 한 줄로 남긴다 — 운영자 행동 이력(DB)이 아니라 애플리케이션 로그다.
 *
 * `Forbidden:` 으로 시작해 검색하기 쉽다. traceId 는 MDC 로 이미 붙으므로 본문에 넣지 않고,
 * 쿼리 문자열·토큰·이메일은 남기지 않는다(주체는 계정 id, 익명이면 `anonymous`).
 */
object ForbiddenLog {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    fun warn(request: HttpServletRequest?, code: String, message: String?) {
        log.warn(
            "Forbidden: {} {} principal={} code={} message={}",
            request?.method ?: "-",
            request?.requestURI ?: "-",
            request?.userPrincipal?.name ?: "anonymous",
            code,
            message,
        )
    }
}
