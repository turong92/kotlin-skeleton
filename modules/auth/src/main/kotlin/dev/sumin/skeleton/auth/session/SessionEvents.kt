package dev.sumin.skeleton.auth.session

enum class SessionEventType { REFRESH_REUSE_DETECTED, SESSION_REVOKED, SESSIONS_REVOKED }

/**
 * 세션에 일어난 일 하나. 토큰은 어디에도 싣지 않는다. [sessionId] 는 한 세션이 아니라 여러 개를 닫은 경우([count])에는 null 이다.
 * [ip] 는 사건을 일으킨 요청의 클라이언트 IP (재사용 탐지 때: 옛 토큰을 내민 쪽).
 */
data class SessionEvent(
    val type: SessionEventType,
    val accountId: String,
    val sessionId: String? = null,
    val count: Int = 0,
    val reason: String? = null,
    val ip: String? = null,
)

/** 세션 이벤트를 듣는다 — 빈으로 등록하면 `auth-session` 이 모두에게 알린다 (`account` 는 계정 이벤트 · 감사 · 경보로 잇는다). 던져도 다른 듣는 쪽에 영향이 없다 */
fun interface SessionEventListener {
    fun on(event: SessionEvent)
}
