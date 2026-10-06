package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.session.OpenedSession
import dev.sumin.skeleton.auth.session.SessionEvent
import dev.sumin.skeleton.auth.session.SessionEventType
import dev.sumin.skeleton.common.time.TimeProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 세션 · 리프레시 토큰 규칙 한 곳. 토큰은 불투명 난수(`r1.` + 256비트 base64url)이고 저장소에는 SHA-256 해시만 간다.
 * 새로고침은 매번 새 토큰을 내고 옛 토큰을 쓴 것으로 표시한다 — 쓴 토큰이 다시 오면 세션 전체를 닫는다(재사용 탐지).
 * 두 요청이 같은 토큰으로 동시에 와도 [SessionStore.markTokenUsed] 의 원자성이 한 쪽만 통과시킨다.
 *
 * [onEvent] 로 재사용 탐지 · 세션 철회를 알린다 (자동설정이 `SessionEventListener` 빈들로 잇는다). 토큰은 어디에도 싣지 않는다.
 * 쓴 토큰은 **세션이 살아 있는 동안** 기억한다 — 도둑이 먼저 쓰고 계속 돌려 쓰는 사이 주인이 며칠 뒤 돌아와도 재사용으로 잡히도록
 * (`reuse-memory` 를 정하면 그 시간만큼만 기억한다: 표가 커지는 것을 막는 대신 그 창이 지나면 탐지가 안 된다).
 */
class SessionService(
    private val store: SessionStore,
    private val properties: AuthSessionProperties,
    private val time: TimeProvider = TimeProvider.systemUtc(),
    /** 회전의 후속 토큰을 만드는 서버 키 — 같은 옛 토큰은 같은 후속 토큰을 낳지만 옛 토큰만으로는 계산할 수 없다. 인스턴스끼리 같아야 한다 (자동설정이 JWT 비밀에서 만든다) */
    tokenKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    private val rotationLimiter: RotationLimiter = RotationLimiter.NONE,
    private val onEvent: (SessionEvent) -> Unit = {},
) {
    private val random = SecureRandom()
    private val rotationKey = SecretKeySpec(tokenKey, "HmacSHA256")

    fun open(accountId: String, client: SessionClient): OpenedSession {
        val now = time.now()
        evictOverflow(accountId, now)
        val id = "ses_" + HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val token = newToken()
        store.create(
            SessionRecord(
                id = id, accountId = accountId, deviceName = client.deviceName?.take(80), userAgent = client.userAgent?.take(255),
                ip = client.ip?.take(64), createdAt = now, lastUsedAt = now, expiresAt = now.plus(properties.absoluteTtl),
            ),
            hash(token),
        )
        return OpenedSession(id, token, now.plus(properties.absoluteTtl))
    }

    fun refresh(rawToken: String, client: SessionClient): RefreshResult {
        val now = time.now()
        if (!rawToken.startsWith(PREFIX) || rawToken.length > MAX_TOKEN) throw RefreshInvalidException()
        val oldHash = hash(rawToken)
        val token = store.findToken(oldHash) ?: throw RefreshInvalidException()
        val session = store.find(token.sessionId) ?: throw RefreshInvalidException()
        if (!isLive(session, now)) throw RefreshInvalidException()
        // 회전마다 토큰 행이 하나 늘어 세션이 끝날 때까지 남는다 — 세션 하나가 창 안에 돌 수 있는 횟수를 묶어 행 수의 상한을 둔다 (유효한 토큰을 낸 세션에만 센다).
        // **이미 쓴 토큰은 한도를 보지 않고** 바로 아래 재사용 규칙으로 간다 — 도둑이 창을 채워 놓고 주인의 옛 토큰을 429 로 덮을 수 없게 (그 경로는 행을 늘리지 않는다)
        if (token.usedAt == null) rotationLimiter.exceeded(session.id)?.let { throw RefreshRateLimitedException(it) }

        var usedAt = token.usedAt
        if (usedAt == null && !store.markTokenUsed(oldHash, now)) {
            // 같은 토큰을 동시에 낸 다른 요청이 먼저 썼다 — 그쪽이 쓴 시각을 다시 읽어 아래 재사용 규칙으로
            usedAt = store.findToken(oldHash)?.usedAt ?: now
        }
        // 유예 안의 재제시(응답을 잃은 브라우저 · 동시 새로고침)는 **같은 후속 토큰**을 돌려준다 — 새 토큰을 또 찍어 갈래를 만들지도, 세션을 닫지도 않는다
        if (usedAt != null && !(properties.reuseGrace.toMillis() > 0 && !usedAt.plus(properties.reuseGrace).isBefore(now))) {
            store.revoke(session.id, now, "REUSE")
            onEvent(SessionEvent(SessionEventType.REFRESH_REUSE_DETECTED, session.accountId, session.id, ip = client.ip))
            throw RefreshReusedException()
        }

        val next = successorOf(rawToken)
        store.addToken(session.id, hash(next), now)   // 멱등 — 같은 후속 토큰이 이미 있으면 그대로
        store.touch(session.id, now, client.ip, client.userAgent)
        properties.reuseMemory?.let { store.pruneUsedTokens(session.id, now.minus(it)) }
        return RefreshResult(session.accountId, OpenedSession(session.id, next, session.expiresAt))
    }

    /** 토큰 하나로 그 세션을 닫는다. 모르는 토큰 · 이미 닫힌 세션도 조용히 (로그아웃은 멱등) */
    fun logout(rawToken: String) {
        if (!rawToken.startsWith(PREFIX) || rawToken.length > MAX_TOKEN) return
        val token = store.findToken(hash(rawToken)) ?: return
        store.revoke(token.sessionId, time.now(), "LOGOUT")
    }

    fun list(accountId: String, currentSessionId: String?): List<SessionView> {
        val now = time.now()
        return store.listActive(accountId, now, now.minus(properties.idleTtl)).map {
            SessionView(it.id, it.deviceName, it.userAgent, it.ip, it.createdAt, it.lastUsedAt, it.id == currentSessionId)
        }
    }

    /** 내 세션만 닫는다. 남의 세션 · 없는 세션은 똑같이 SESSION_NOT_FOUND — 존재를 더듬지 못하게 */
    fun revoke(accountId: String, sessionId: String) {
        val session = store.find(sessionId)
        if (session == null || session.accountId != accountId || !store.revoke(sessionId, time.now(), "REVOKED")) throw SessionNotFoundException()
        onEvent(SessionEvent(SessionEventType.SESSION_REVOKED, accountId, sessionId, count = 1))
    }

    fun revokeAll(accountId: String, exceptSessionId: String?, reason: String = "REVOKED_ALL") {
        val n = store.revokeAll(accountId, exceptSessionId, time.now(), reason)
        if (n > 0) onEvent(SessionEvent(SessionEventType.SESSIONS_REVOKED, accountId, count = n, reason = reason))
    }

    private fun isLive(s: SessionRecord, now: Instant): Boolean =
        s.revokedAt == null && s.expiresAt.isAfter(now) && s.lastUsedAt.plus(properties.idleTtl).isAfter(now)

    private fun evictOverflow(accountId: String, now: Instant) {
        val live = store.listActive(accountId, now, now.minus(properties.idleTtl))
        val overflow = live.size - (properties.maxSessionsPerAccount - 1)
        if (overflow > 0) live.sortedBy { it.createdAt }.take(overflow).forEach { store.revoke(it.id, now, "EVICTED") }
    }

    /** 옛 토큰 → 후속 토큰 (HMAC, 결정적). 옛 토큰의 해시가 아니라 원문으로 계산하므로 DB 만으로는 만들 수 없다 */
    fun successorOf(oldRawToken: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(rotationKey) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("rotate\n" + oldRawToken).toByteArray(Charsets.UTF_8)))
    }

    private fun newToken(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    companion object {
        const val PREFIX = "r1."
        private const val MAX_TOKEN = 128

        fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    }
}
