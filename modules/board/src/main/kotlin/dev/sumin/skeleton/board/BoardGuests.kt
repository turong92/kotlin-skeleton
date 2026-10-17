package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import java.security.MessageDigest
import java.time.Clock
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * 로그인하지 않고 쓰는 요청 한 건의 맥락 — HTTP 어댑터가 만든다. [secret] 은 헤더 `X-Board-Guest-Secret` 의 값(없으면 null),
 * [limitKey] 는 `ClientIps` 의 요청 한도 키, [ip] 는 캡차 확인용, [captchaToken] 은 만들기 본문의 토큰.
 * 비밀은 로그 · `toString` 에 나가지 않는다.
 */
class GuestContext(
    val secret: String?,
    val limitKey: String,
    val ip: String? = null,
    val captchaToken: String? = null,
) {
    override fun toString() = "GuestContext(limitKey=$limitKey)"
}

/** 비회원 비밀의 해시 방식 — 앱이 같은 타입의 빈으로 argon2 등을 끼운다. 저장값의 접두사가 방식을 적는다 */
interface BoardGuestSecrets {
    /** 받은 값의 모양을 검사한다 (틀리면 [BoardErrorCode.CONTENT_INVALID]) */
    fun validate(raw: String)

    fun hash(raw: String): String

    fun matches(raw: String, stored: String): Boolean
}

/**
 * 기본 구현. [BoardProperties.Guest.secret] 이 **새로 받는 값의 모양과 새 해시 방식**을 정하고, 확인은 저장값의 접두사를 따른다 —
 * 설정을 바꿔도 옛 행은 저장된 방식대로 확인된다. PASSWORD: 사람이 치는 짧은 값 → bcrypt(느린 해시). TOKEN: 무작위 256비트 → SHA-256.
 */
class DefaultBoardGuestSecrets(private val mode: GuestSecretMode) : BoardGuestSecrets {
    private val bcrypt = BCryptPasswordEncoder(10)

    override fun validate(raw: String) {
        when (mode) {
            GuestSecretMode.PASSWORD ->
                if (raw.length < 4 || raw.toByteArray(Charsets.UTF_8).size > 72) invalid("guest secret must be 4..72 bytes")
            GuestSecretMode.TOKEN ->
                if (!TOKEN.matches(raw)) invalid("guest secret must be 32..128 characters of A-Z a-z 0-9 _ -")
        }
    }

    override fun hash(raw: String): String = when (mode) {
        GuestSecretMode.PASSWORD -> BCRYPT + bcrypt.encode(raw)
        GuestSecretMode.TOKEN -> SHA256 + sha256(raw)
    }

    override fun matches(raw: String, stored: String): Boolean = when {
        stored.startsWith(BCRYPT) -> raw.toByteArray(Charsets.UTF_8).size <= 72 && bcrypt.matches(raw, stored.removePrefix(BCRYPT))
        stored.startsWith(SHA256) -> MessageDigest.isEqual(sha256(raw).toByteArray(), stored.removePrefix(SHA256).toByteArray())
        else -> false
    }

    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun invalid(message: String): Nothing = throw BoardException(BoardErrorCode.CONTENT_INVALID, message)

    private companion object {
        const val BCRYPT = "{bcrypt}"
        const val SHA256 = "{sha256}"
        val TOKEN = Regex("^[A-Za-z0-9_-]{32,128}$")
    }
}

/** 비회원 쓰기 한도와 비밀 대입 한도. `guest.enabled` 가 아니면 부를 일이 없다. 한도는 `rate-limit.enabled` 와 무관하게 늘 걸린다 */
interface BoardGuestLimiter {
    /** 만들기 · 고치기 · 지우기 한 번 — [limitKey] 는 `ClientIps` 한도 키 */
    fun tryWrite(limitKey: String): Boolean

    /** 비밀 확인 시도 한 번. [row] 는 `p<id>` · `c<id>` — 행마다와 IP 마다 따로 센다. 해시를 계산하기 **전에** 부른다 */
    fun trySecretAttempt(row: String, limitKey: String): Boolean
}

class StoreBoardGuestLimiter(
    private val store: () -> RateLimitStore,
    private val properties: BoardProperties.Guest,
    private val clock: Clock = Clock.systemUTC(),
) : BoardGuestLimiter {
    override fun tryWrite(limitKey: String): Boolean =
        consume("board:guest-write:$limitKey", properties.rateLimit.capacity, properties.rateLimit.window.toMillis())

    override fun trySecretAttempt(row: String, limitKey: String): Boolean {
        val a = properties.secretAttempts
        val perRow = consume("board:guest-secret:row:$row", a.capacity, a.window.toMillis())
        val perIp = consume("board:guest-secret:ip:$limitKey", a.capacity, a.window.toMillis())
        return perRow && perIp
    }

    private fun consume(key: String, capacity: Int, windowMillis: Long): Boolean =
        store().consume(key = key, capacity = capacity, windowMillis = windowMillis, now = clock.instant()).allowed
}

/** 비회원 만들기의 캡차 확인 고리 — `captcha-turnstile` 이 있으면 그 검증기로 이어진다. 켜는 것은 `guest.captcha=REQUIRED` */
fun interface BoardCaptcha {
    fun verify(token: String?, ip: String?, action: String): Boolean
}

/**
 * 비회원 작성의 공통 규칙 한 곳 — 서비스들이 쓴다. 비밀은 서비스가 헤더에서 받아 [GuestContext] 로 넘기고, 여기서만 해시한다.
 * 순서(스펙 §7): 만들기는 본문 검사 → 캡차 → 한도 → 해시. 고치기 · 지우기는 한도 → 해시 확인(대입 한도는 해시 전에).
 */
class BoardGuests(
    private val properties: BoardProperties,
    private val secrets: BoardGuestSecrets = DefaultBoardGuestSecrets(properties.guest.secret),
    private val limiter: BoardGuestLimiter = StoreBoardGuestLimiter({ FALLBACK_STORE }, properties.guest),
    private val captcha: () -> BoardCaptcha? = { null },
    private val ids: SortableIds = SortableIds.system,
) {
    /** `guest:<ULID>` — 행마다 새 값 */
    fun newAuthorId(): String = BoardAuthors.GUEST_PREFIX + ids.newId()

    /** 로그인하지 않은 쓰기가 비회원으로도 받아들여지지 않을 때 — 지금까지와 같은 401 */
    fun unauthorized() = ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)

    /** 만들기에서 받는 비밀의 모양을 검사한다 (본문 검사 단계) */
    fun validateNewSecret(guest: GuestContext): String {
        val raw = guest.secret ?: invalid("X-Board-Guest-Secret header is required")
        secrets.validate(raw)
        return raw
    }

    /** 캡차(켠 경우) → 쓰기 한도. 한도를 넘으면 [BoardErrorCode.RATE_LIMITED] */
    fun admit(guest: GuestContext, checkCaptcha: Boolean) {
        if (checkCaptcha && properties.guest.captcha == GuestCaptcha.REQUIRED) {
            val verifier = captcha()
            if (verifier == null || !verifier.verify(guest.captchaToken, guest.ip, "board-guest")) {
                throw BoardException(BoardErrorCode.CAPTCHA_FAILED, "Captcha verification failed")
            }
        }
        if (!limiter.tryWrite(guest.limitKey)) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many requests, slow down")
    }

    fun hash(raw: String): String = secrets.hash(raw)

    /**
     * [row] 의 저장된 해시와 헤더의 비밀이 맞는가. 대입 한도를 **해시 계산 전에** 센다 (넘으면 429). 비회원 행이 아니거나([storedHash] 가 null) 헤더가 없으면 false —
     * 호출한 쪽은 틀린 비밀과 같은 403 으로 답한다.
     */
    fun verify(guest: GuestContext, row: String, storedHash: String?): Boolean {
        val raw = guest.secret
        if (storedHash == null || raw == null) return false
        if (!limiter.trySecretAttempt(row, guest.limitKey)) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many attempts, try later")
        return secrets.matches(raw, storedHash)
    }

    /** 이름: 한 줄로 정리하고 1..[BoardProperties.Guest.nameMaxLength] 코드 포인트 */
    fun name(raw: String?, rules: BoardContentRules): String = rules.guestName(raw, properties.guest.nameMaxLength)

    private fun invalid(message: String): Nothing = throw BoardException(BoardErrorCode.CONTENT_INVALID, message)

    companion object {
        /** 비밀이 틀린 경우 · 남의 행 · 회원 행 · 헤더 없음이 모두 같은 문구로 답한다 */
        const val FORBIDDEN_MESSAGE = "Not allowed"
        private val FALLBACK_STORE by lazy { InMemoryFixedWindowRateLimitStore() }
    }
}
