package dev.sumin.skeleton.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

/**
 * 4xx · 5xx 로 답한 요청이 **왜** 그랬는지 한 줄 — [RequestLoggingFilter] 의 종료 줄(`skeleton.debug.request`)에 `code=… reason=…` 으로 붙는다.
 * 응답 본문은 로그에 없어서 "400 인데 이유를 모르겠다" 가 되던 것을 막는다. 오류 응답을 만드는 곳([GlobalExceptionHandler], 인터셉터가 직접 쓰는 오류)이 적는다.
 *
 * 싣는 것은 오류 코드와 **고정 제목**뿐이다 — 요청 본문 · 헤더 값 · 토큰 · 개인정보는 싣지 않는다(호출하는 쪽이 그런 값을 reason 에 넣지 않는다).
 * 한 줄로 만들고 길이를 자른다.
 */
object RequestErrorNote {
    const val ATTRIBUTE = "skeleton.request.error-note"
    private const val MAX_REASON = 160

    data class Note(val code: String, val reason: String)

    fun record(request: HttpServletRequest, code: String, reason: String) {
        request.setAttribute(ATTRIBUTE, Note(code.take(80), oneLine(reason)))
    }

    /** 요청 스레드에 [RequestContextHolder] 가 있을 때(MVC 디스패치 안) — 없으면 아무것도 하지 않는다 */
    fun recordCurrent(code: String, reason: String) {
        RequestContextHolder.getRequestAttributes()?.setAttribute(ATTRIBUTE, Note(code.take(80), oneLine(reason)), RequestAttributes.SCOPE_REQUEST)
    }

    fun of(request: HttpServletRequest): Note? = request.getAttribute(ATTRIBUTE) as? Note

    private fun oneLine(text: String): String =
        text.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(MAX_REASON)
}
