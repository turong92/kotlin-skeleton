package dev.sumin.skeleton.account.token

import dev.sumin.skeleton.common.time.TimeProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/** 한 번 쓰는 토큰의 용도 — 문자열이라 다른 모듈(매직 링크 …)이 자기 용도를 더한다. 용도가 다르면 같은 토큰이라도 쓸 수 없다 */
object TokenPurposes {
    const val VERIFY_EMAIL = "verify_email"
    const val PASSWORD_RESET = "password_reset"
    const val EMAIL_CHANGE = "email_change"
    const val MAGIC_LINK = "magic_link"
    const val DELETE_CONFIRM = "delete_confirm"

    /** 비밀번호 없는 계정이 민감한 일(이메일 변경 · 첫 비밀번호 · 소셜 연결)을 하기 전에 메일함으로 다시 인증한다 */
    const val REAUTH = "reauth"
}

/** 저장소에 있는 토큰 한 줄. 원문이 아니라 SHA-256 [hash] 만 있다 */
data class TokenRow(
    val hash: String,
    val purpose: String,
    /** 이 용도 안에서 토큰의 주인을 가리키는 값 (인증 · 재설정 · 매직 링크: 이메일, 이메일 변경 · 삭제 확인: 계정 id) — 새 토큰이 같은 (용도, 주인)의 옛 토큰을 닫는다 */
    val subject: String,
    val accountId: String?,
    val payload: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant? = null,
)

/** 쓸 수 있는 토큰이 밝혀 주는 것 */
data class TokenGrant(val purpose: String, val subject: String, val accountId: String?, val payload: String?, val expiresAt: Instant)

interface OneTimeTokenStore {
    fun insert(row: TokenRow)

    /** 만료 · 소비와 상관없이 해시로 한 줄 — 용도가 맞는지는 호출자가 본다 */
    fun find(hash: String): TokenRow?

    /**
     * 용도가 맞고 · 아직 안 쓰고 · 만료 전이면 [now] 로 쓴 것으로 표시하고 그 줄을 돌려준다 — **원자적**이어야 한다
     * (`update … set consumed_at = :now where token_hash = :h and purpose = :p and consumed_at is null and expires_at > :now` 의 갱신 행 수 1).
     * 용도가 틀리면 아무것도 바꾸지 않는다.
     */
    fun consume(hash: String, purpose: String, now: Instant): TokenRow?

    /** 같은 (용도, 주인)의 아직 안 쓴 토큰을 닫는다 */
    fun invalidateOpen(purpose: String, subject: String, now: Instant): Int

    /** 만료가 [before] 보다 이른 줄을 지운다 — 청소용 */
    fun purgeExpired(before: Instant): Int
}

/**
 * 이메일 인증 · 비밀번호 재설정 · 이메일 변경 · 매직 링크 · 삭제 확인이 **같이 쓰는** 한 번 쓰는 토큰 장치.
 * 토큰은 256비트 난수이고 저장소에는 해시만 간다 — DB 가 새도 링크를 만들 수 없다. 로그에도 싣지 않는다.
 */
class OneTimeTokens(
    private val store: OneTimeTokenStore,
    private val time: TimeProvider = TimeProvider.systemUtc(),
) {
    private val random = SecureRandom()

    /** 새 토큰 원문을 돌려준다 (메일 본문에만 쓴다). 같은 (용도, [subject]) 의 옛 토큰은 닫힌다 */
    fun issue(purpose: String, subject: String, accountId: String?, ttl: Duration, payload: String? = null): String {
        val now = time.now()
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        store.invalidateOpen(purpose, subject, now)
        store.insert(TokenRow(hash(raw), purpose, subject, accountId, payload, now, now.plus(ttl)))
        return raw
    }

    /** 같은 (용도, [subject]) 의 아직 안 쓴 토큰을 모두 닫는다 — 비밀번호가 바뀌었거나 메일함이 다른 길로 증명됐을 때, 그 전에 나간 링크가 살아 있지 않게 */
    fun invalidate(purpose: String, subject: String): Int = store.invalidateOpen(purpose, subject, time.now())

    /** 쓰지 않고 유효한지만 본다 (정책 검사 뒤에 쓰려고 — 정책 실패가 토큰을 태우지 않게) */
    fun peek(purpose: String, raw: String): TokenGrant? {
        val row = lookup(raw)?.takeIf { it.purpose == purpose && it.consumedAt == null && it.expiresAt.isAfter(time.now()) } ?: return null
        return row.grant()
    }

    /** 한 번만 성공한다 — 두 요청이 동시에 와도 하나만 */
    fun consume(purpose: String, raw: String): TokenGrant? {
        if (!plausible(raw)) return null
        return store.consume(hash(raw), purpose, time.now())?.grant()
    }

    /** 만료된 지 [retention] 이 지난 토큰 줄을 지운다 (청소 — 지운 수를 돌려준다) */
    fun sweep(retention: Duration): Int = store.purgeExpired(time.now().minus(retention))

    private fun lookup(raw: String): TokenRow? = if (plausible(raw)) store.find(hash(raw)) else null

    private fun plausible(raw: String) = raw.length in 20..MAX_LENGTH && raw.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun TokenRow.grant() = TokenGrant(purpose, subject, accountId, payload, expiresAt)

    companion object {
        private const val MAX_LENGTH = 128

        /** 이메일 인증 링크가 어느 비밀번호용인지 묶는 지문 — 해시를 토큰 payload 에 그대로 두지 않는다 */
        fun fingerprint(secret: String): String = hash(secret)

        fun hash(raw: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
    }
}

/** 단일 인스턴스 · 시험용 기본 저장소 (재시작하면 열린 링크가 사라진다 — 보호 환경 가드가 문제로 본다) */
class InMemoryOneTimeTokenStore : OneTimeTokenStore {
    private val rows = LinkedHashMap<String, TokenRow>()

    @Synchronized override fun insert(row: TokenRow) { rows[row.hash] = row }

    @Synchronized override fun find(hash: String): TokenRow? = rows[hash]

    @Synchronized override fun consume(hash: String, purpose: String, now: Instant): TokenRow? {
        val row = rows[hash] ?: return null
        if (row.purpose != purpose || row.consumedAt != null || !row.expiresAt.isAfter(now)) return null
        rows[hash] = row.copy(consumedAt = now)
        return row
    }

    @Synchronized override fun invalidateOpen(purpose: String, subject: String, now: Instant): Int {
        var n = 0
        rows.values.toList().filter { it.purpose == purpose && it.subject == subject && it.consumedAt == null }.forEach { rows[it.hash] = it.copy(consumedAt = now); n++ }
        return n
    }

    @Synchronized override fun purgeExpired(before: Instant): Int {
        val dead = rows.values.filter { it.expiresAt.isBefore(before) }.map { it.hash }
        dead.forEach { rows.remove(it) }
        return dead.size
    }

    /** 시험용 — 저장된 해시 전부 */
    @Synchronized fun hashes(): List<String> = rows.keys.toList()
}
