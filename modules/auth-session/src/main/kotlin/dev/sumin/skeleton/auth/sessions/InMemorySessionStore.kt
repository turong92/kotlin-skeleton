package dev.sumin.skeleton.auth.sessions

import java.time.Instant

/** 단일 인스턴스 · 시험용 기본 저장소 — 재시작하면 모든 세션이 사라지고 인스턴스끼리 나누지 못한다 (stage · prod 의 DeployGuard 가 문제로 본다) */
class InMemorySessionStore : SessionStore {
    private val sessions = LinkedHashMap<String, SessionRecord>()
    private class Tok(val sessionId: String, var usedAt: Instant?)
    private val tokens = LinkedHashMap<String, Tok>()

    @Synchronized override fun create(session: SessionRecord, firstTokenHash: String) {
        sessions[session.id] = session
        tokens[firstTokenHash] = Tok(session.id, null)
    }

    @Synchronized override fun findToken(hash: String): TokenRecord? = tokens[hash]?.let { TokenRecord(it.sessionId, it.usedAt) }

    @Synchronized override fun markTokenUsed(hash: String, now: Instant): Boolean {
        val t = tokens[hash] ?: return false
        if (t.usedAt != null) return false
        t.usedAt = now
        return true
    }

    @Synchronized override fun addToken(sessionId: String, hash: String, now: Instant) {
        tokens[hash] = Tok(sessionId, null)
    }

    @Synchronized override fun find(sessionId: String): SessionRecord? = sessions[sessionId]

    @Synchronized override fun touch(sessionId: String, now: Instant, ip: String?, userAgent: String?) {
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(lastUsedAt = now, ip = ip ?: it.ip, userAgent = userAgent ?: it.userAgent) }
    }

    @Synchronized override fun revoke(sessionId: String, now: Instant, reason: String): Boolean {
        val s = sessions[sessionId] ?: return false
        if (s.revokedAt != null) return false
        sessions[sessionId] = s.copy(revokedAt = now, revokedReason = reason)
        return true
    }

    @Synchronized override fun revokeAll(accountId: String, exceptSessionId: String?, now: Instant, reason: String): Int {
        var n = 0
        sessions.values.toList().filter { it.accountId == accountId && it.id != exceptSessionId && it.revokedAt == null }.forEach {
            sessions[it.id] = it.copy(revokedAt = now, revokedReason = reason); n++
        }
        return n
    }

    @Synchronized override fun listActive(accountId: String, now: Instant, idleCutoff: Instant): List<SessionRecord> =
        sessions.values.filter { it.accountId == accountId && it.revokedAt == null && it.expiresAt.isAfter(now) && it.lastUsedAt.isAfter(idleCutoff) }
            .sortedWith(compareByDescending<SessionRecord> { it.createdAt }.thenByDescending { it.id })

    @Synchronized override fun pruneUsedTokens(sessionId: String, usedBefore: Instant) {
        tokens.entries.removeIf { (_, t) -> t.sessionId == sessionId && t.usedAt != null && t.usedAt!!.isBefore(usedBefore) }
    }

    @Synchronized override fun purge(before: Instant): Int {
        val dead = sessions.values.filter { (it.revokedAt != null && it.revokedAt.isBefore(before)) || it.expiresAt.isBefore(before) }.map { it.id }.toSet()
        dead.forEach { sessions.remove(it) }
        tokens.entries.removeIf { it.value.sessionId in dead }
        return dead.size
    }

    @Synchronized override fun eraseAccount(accountId: String): Int {
        val mine = sessions.values.filter { it.accountId == accountId }.map { it.id }.toSet()
        mine.forEach { sessions.remove(it) }
        tokens.entries.removeIf { it.value.sessionId in mine }
        return mine.size
    }

    /** 시험용 — 저장된 토큰 해시 전부 (원문이 저장되지 않는다는 것을 보인다) */
    @Synchronized fun tokenHashes(): List<String> = tokens.keys.toList()
}
