package dev.sumin.skeleton.common

import dev.sumin.skeleton.common.logging.LogMasker
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory

/**
 * 권한 때문에 거절된(403) 요청을 로그 한 줄로 남긴다 — 운영자 행동 이력(DB)이 아니라 애플리케이션 로그다.
 *
 * `Forbidden:` 으로 시작해 검색하기 쉽다. traceId 는 MDC 로 이미 붙으므로 본문에 넣지 않고,
 * 쿼리 문자열·토큰·이메일은 남기지 않는다.
 * 경로는 [LogMasker.maskRequestPath] 를 거친다(요청 로그와 같은 규칙).
 *
 * 주체는 `Authentication.name` 이다 — 기본 auth 에서는 계정 id, 익명이면 `anonymous`, 요청을 못 구하면 `-`.
 * 보안 체인을 바꾸는 앱은 name 이 계정 id 를 돌려주게 해야 한다(이메일 등이 로그에 남지 않도록).
 */
object ForbiddenLog {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    fun warn(request: HttpServletRequest?, code: String, message: String?) {
        log.warn(
            "Forbidden: {} {} principal={} code={} message={}",
            request?.method ?: "-",
            request?.let { LogMasker.current.maskRequestPath(it.requestURI) } ?: "-",
            if (request == null) "-" else request.userPrincipal?.name ?: "anonymous",
            code,
            message?.let(::oneLine),
        )
    }

    private fun oneLine(text: String): String = text.replace(Regex("[\\r\\n\\u0000-\\u001f\\u2028\\u2029]+"), " ").trim()
}
