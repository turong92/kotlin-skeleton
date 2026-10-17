package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
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
                if (raw.length < 4 || raw.toByteArray(Charsets.UTF_8).size > 72) invalid("guest secret must be at least 4 characters and at most 72 bytes")
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

    /**
     * 비밀을 확인해 봐도 되는가 — **해시를 계산하기 전에 원자적으로 센다**(동시에 몰려도 한도 밖은 해시를 돌리지 못한다). [row] 는 `p<id>` · `c<id>`, 행마다와 IP 마다 따로 센다.
     * 맞았으면 [releaseSecretAttempt] 로 되돌린다 — 맞는 비밀로 승인 · 관리를 거듭해도 한도가 닳지 않는다(저장소가 `release` 를 지원할 때; 아니면 성공도 센 채 남는다).
     */
    fun trySecretAttempt(row: String, limitKey: String, at: Instant): Boolean

    /** [trySecretAttempt] 가 센 한 번을 되돌린다 — 비밀이 **맞았을 때만**. [at] 은 센 때의 시각 - 그때의 창에서 되돌린다(해시 계산 사이에 창이 넘어가도 새 창을 깎지 않는다) */
    fun releaseSecretAttempt(row: String, limitKey: String, at: Instant)

    /**
     * 비밀을 확인하는 일(해시 계산) 자체의 IP 별 한도 - **맞아도 되돌리지 않는다**. 대입 한도는 맞는 비밀을 되돌려 주므로 맞는 비밀로 계속 보내면 bcrypt 를 한도 없이 돌릴 수 있다.
     * 기본 구현은 제한하지 않는다(기존 구현체가 그대로 컴파일된다).
     */
    fun tryVerification(limitKey: String, at: Instant): Boolean = true
}

class StoreBoardGuestLimiter(
    private val store: () -> RateLimitStore,
    private val properties: BoardProperties.Guest,
    private val clock: Clock = Clock.systemUTC(),
) : BoardGuestLimiter {
    override fun tryWrite(limitKey: String): Boolean =
        consume("board:guest-write:$limitKey", properties.rateLimit.capacity, properties.rateLimit.window.toMillis())

    override fun trySecretAttempt(row: String, limitKey: String, at: Instant): Boolean {
        val a = properties.secretAttempts
        val perRow = consume("board:guest-secret:row:$row", a.capacity, a.window.toMillis(), at)
        val perIp = consume("board:guest-secret:ip:$limitKey", a.capacity, a.window.toMillis(), at)
        return perRow && perIp
    }

    override fun tryVerification(limitKey: String, at: Instant): Boolean {
        val v = properties.secretVerifications
        return consume("board:guest-verify:$limitKey", v.capacity, v.window.toMillis(), at)
    }

    override fun releaseSecretAttempt(row: String, limitKey: String, at: Instant) {
        val a = properties.secretAttempts
        store().release("board:guest-secret:row:$row", a.window.toMillis(), at)
        store().release("board:guest-secret:ip:$limitKey", a.window.toMillis(), at)
    }

    private fun consume(key: String, capacity: Int, windowMillis: Long, at: Instant = clock.instant()): Boolean =
        store().consume(key = key, capacity = capacity, windowMillis = windowMillis, now = at).allowed
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
    private val clock: () -> Instant = Instant::now,
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
        val at = clock()
        val remembered = verifiedKey(row, raw)
        if (remembered != null && verified[remembered]?.isAfter(at) == true) return true   // 방금 맞은 비밀 - 다시 해시하지 않는다
        // 해시를 돌리기 전에: 확인 한도(맞아도 되돌리지 않는다) -> 대입 한도(맞으면 되돌린다)
        if (!limiter.tryVerification(guest.limitKey, at)) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many requests, slow down")
        if (!limiter.trySecretAttempt(row, guest.limitKey, at)) throw BoardException(BoardErrorCode.RATE_LIMITED, "Too many attempts, try later")
        val ok = secrets.matches(raw, storedHash)
        if (ok) {
            limiter.releaseSecretAttempt(row, guest.limitKey, at)
            remember(remembered, at)
        }
        return ok
    }

    /**
     * 맞은 (행, 비밀) 의 기억 - 키는 이 프로세스만 아는 무작위 키로 만든 HMAC 이다(비밀 원문은 어디에도 두지 않는다). 프로젝트마다 따로 센다.
     * 지금 켜져 있으면 키를, 꺼져 있으면 null.
     */
    private fun verifiedKey(row: String, raw: String): String? {
        if (properties.guest.verifiedCacheTtl.isZero) return null
        val project = dev.sumin.skeleton.common.project.ProjectContext.currentOrNull()?.id.orEmpty()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(cacheKey, "HmacSHA256")) }
        return mac.doFinal("$project\n$row\n$raw".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun remember(key: String?, at: Instant) {
        key ?: return
        if (verified.size >= VERIFIED_MAX) {
            verified.entries.removeIf { !it.value.isAfter(at) }
            if (verified.size >= VERIFIED_MAX) verified.clear()
        }
        verified[key] = at.plus(properties.guest.verifiedCacheTtl)
    }

    private val cacheKey: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    private val verified = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /** 이름: 한 줄로 정리하고 1..[BoardProperties.Guest.nameMaxLength] 코드 포인트 */
    fun name(raw: String?, rules: BoardContentRules): String = rules.guestName(raw, properties.guest.nameMaxLength)

    private fun invalid(message: String): Nothing = throw BoardException(BoardErrorCode.CONTENT_INVALID, message)

    companion object {
        /** 비밀이 틀린 경우 · 남의 행 · 회원 행 · 헤더 없음이 모두 같은 문구로 답한다 */
        const val FORBIDDEN_MESSAGE = "Not allowed"
        private const val VERIFIED_MAX = 10_000
        private val FALLBACK_STORE by lazy { InMemoryFixedWindowRateLimitStore() }
    }
}
