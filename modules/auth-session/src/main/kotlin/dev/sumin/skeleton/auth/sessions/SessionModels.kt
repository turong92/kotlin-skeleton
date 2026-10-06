package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.session.OpenedSession
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import java.time.Instant
import org.springframework.http.HttpStatus

enum class AuthSessionErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    REFRESH_INVALID("AUTH.REFRESH_INVALID", HttpStatus.UNAUTHORIZED, "Invalid refresh token", "The refresh token is unknown, expired or revoked"),
    REFRESH_REUSED("AUTH.REFRESH_REUSED", HttpStatus.UNAUTHORIZED, "Refresh token reused", "The refresh token was already used; the session was closed"),
    SESSION_NOT_FOUND("AUTH.SESSION_NOT_FOUND", HttpStatus.NOT_FOUND, "Session not found", "Session not found"),
    CSRF_HEADER_REQUIRED("AUTH.CSRF_HEADER_REQUIRED", HttpStatus.FORBIDDEN, "Request header required", "Cookie-based refresh needs the CSRF header"),
}

class RefreshInvalidException : ApplicationException("Refresh token is invalid", AuthSessionErrorCode.REFRESH_INVALID)

class RefreshReusedException : ApplicationException("Refresh token was reused", AuthSessionErrorCode.REFRESH_REUSED)

class SessionNotFoundException : ApplicationException("Session not found", AuthSessionErrorCode.SESSION_NOT_FOUND)

/** 요청에서 읽은 기기 정보 — 세션 목록에 보인다. [ip] 는 `ClientIps` 가 푼 값 */
data class SessionClient(val ip: String?, val userAgent: String?, val deviceName: String?)

/** 저장소가 들고 있는 세션. 리프레시 토큰의 원문 · 해시는 여기 없다 */
data class SessionRecord(
    val id: String,
    val accountId: String,
    val deviceName: String?,
    val userAgent: String?,
    val ip: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val revokedReason: String? = null,
)

/** 세션 목록의 한 줄 (HTTP 로도 그대로 나간다) */
data class SessionView(
    val id: String,
    val deviceName: String?,
    val userAgent: String?,
    val ip: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val current: Boolean,
)

data class TokenRecord(val sessionId: String, val usedAt: Instant?)

data class RefreshResult(val accountId: String, val session: OpenedSession)

/**
 * 세션 · 리프레시 토큰 저장소 포트. 토큰은 **해시(SHA-256 hex)로만** 들어온다.
 * [markTokenUsed] 는 한 토큰을 두 번 쓰지 못하게 하는 유일한 지점이다 — 구현은 "아직 안 쓴 행을 쓴 것으로" 를 원자적으로(조건부 UPDATE · 락) 해야 하고,
 * 진 쪽에는 false 를 돌려줘야 한다.
 */
interface SessionStore {
    fun create(session: SessionRecord, firstTokenHash: String)

    fun findToken(hash: String): TokenRecord?

    /** 아직 안 쓴 토큰이면 [now] 로 쓴 것으로 표시하고 true, 이미 썼거나 없으면 false (원자적) */
    fun markTokenUsed(hash: String, now: Instant): Boolean

    /** 새 토큰 행 — **멱등**: 같은 해시가 이미 있으면 아무것도 하지 않는다 (유예 안의 재제시가 같은 후속 토큰을 다시 넣는다) */
    fun addToken(sessionId: String, hash: String, now: Instant)

    fun find(sessionId: String): SessionRecord?

    fun touch(sessionId: String, now: Instant, ip: String?, userAgent: String?)

    /** 아직 열린 세션이면 닫고 true */
    fun revoke(sessionId: String, now: Instant, reason: String): Boolean

    fun revokeAll(accountId: String, exceptSessionId: String?, now: Instant, reason: String): Int

    /** 열린(철회 안 됨 · 절대 수명 안 지남 · 놀지 않은) 세션, 최근에 만든 것부터 */
    fun listActive(accountId: String, now: Instant, idleCutoff: Instant): List<SessionRecord>

    /** 이 세션에서 [usedBefore] 이전에 쓴 토큰 행을 지운다 (재사용 기억 기간 밖) */
    fun pruneUsedTokens(sessionId: String, usedBefore: Instant)

    /** 끝난 세션(철회됐거나 절대 수명이 지난 것 중 [before] 이전)과 그 토큰을 지운다 — 청소용 */
    fun purge(before: Instant): Int

    /** 계정의 세션을 상태와 상관없이 전부, 토큰과 함께 지운다 (계정 삭제 — IP · UA 가 남지 않게). 지운 세션 수. 멱등 */
    fun eraseAccount(accountId: String): Int
}
