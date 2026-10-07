package dev.sumin.skeleton.account.challenge

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 코드 챌린지의 용도 — 세션 안에서 입력하는 코드(이메일 변경 · 다시 인증 · 삭제 확인)와 가입 시도(로그인 전, 가입 id 로 찾는다) */
object ChallengePurposes {
    const val SIGN_UP = "sign_up"
    const val EMAIL_CHANGE = "email_change"
    const val REAUTH = "reauth"
    const val DELETE_CONFIRM = "delete_confirm"
}

/**
 * 코드 챌린지 한 줄 — 6자리 숫자 코드의 **해시**만 있다. [id] 는 가입 시도면 가입 id(핸들)의 SHA-256, 세션 코드면 무작위 값.
 * [secret] 은 가입 시도의 비밀번호 해시(그 시도에서 입력된 것) — 시도가 확인되기 전에는 계정의 어떤 자격도 쓰이지 않는다.
 */
data class ChallengeRow(
    val id: String,
    val purpose: String,
    /** 가입: 정규화된 이메일 / 그 밖: 계정 id — (용도, 주인) 으로 열려 있는 최근 챌린지를 찾는다 */
    val subject: String,
    val accountId: String?,
    val sessionId: String?,
    val payload: String?,
    val secret: String?,
    val codeHash: String,
    val attemptsLeft: Int,
    val resends: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastSentAt: Instant,
    val ip: String?,
) {
    override fun toString() = "ChallengeRow(id=$id, purpose=$purpose, attemptsLeft=$attemptsLeft, secret=${if (secret == null) "none" else "<redacted>"})"
}

/** 챌린지 저장소 포트 — 시도 차감 · 삭제는 **원자적**이어야 한다 (동시 추측이 시도 수를 넘기지 못하게) */
interface ChallengeStore {
    fun insert(row: ChallengeRow)

    fun find(id: String): ChallengeRow?

    /** (용도, 주인) 의 만료 전 가장 최근 챌린지 */
    fun findOpen(purpose: String, subject: String, now: Instant): ChallengeRow?

    /** 시도가 남았고 만료 전이면 시도를 하나 깎고 **깎은 뒤의** 줄을 돌려준다 (`update … set attempts_left = attempts_left - 1 where id = :id and attempts_left > 0 and expires_at > :now`). 아니면 null */
    fun spendAttempt(id: String, now: Instant): ChallengeRow?

    /** 줄을 지운다. 이 호출이 지웠으면 true — 한 챌린지를 두 요청이 동시에 이겨도 하나만 true */
    fun delete(id: String): Boolean

    fun deleteBySubject(purpose: String, subject: String): Int

    fun deleteByAccount(accountId: String, purposes: Collection<String>): Int

    /** 새 코드로 바꾼다 — 마지막 발송이 [sentBefore] 이전이고 재전송이 [maxResends] 미만일 때만 (**이미 만료된 줄도** 된다 — 청소 전이면 새 코드로 되살아난다). 시도는 [attempts] 로 되돌린다 */
    fun replaceCode(id: String, codeHash: String, expiresAt: Instant, now: Instant, sentBefore: Instant, maxResends: Int, attempts: Int): Boolean

    fun purgeExpired(before: Instant): Int
}

/**
 * 코드의 키 달린 해시 — HMAC-SHA256(서버 비밀, 챌린지 id + ":" + 코드). 6자리 코드는 공간이 100만이라 **느린 해시로도 DB 유출에서 지킬 수 없다**
 * (100만 번이면 끝난다) — 지키는 것은 서버 비밀이다: DB 만 새어서는 코드를 되돌릴 수 없다. 비교는 상수 시간.
 */
class CodeHasher(private val key: ByteArray) {
    fun hash(challengeId: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal("$challengeId:$code".toByteArray(Charsets.UTF_8)))
    }

    fun matches(expected: String, challengeId: String, code: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), hash(challengeId, code).toByteArray(Charsets.UTF_8))
}

/** [Challenges.open] 의 결과 — [code] 는 메일 본문에만 쓴다. [handle] 은 가입 시도(클라이언트가 들고 있는 가입 id)만 */
class Opened(val handle: String?, val code: String, val row: ChallengeRow) {
    override fun toString() = "Opened(row=$row)"
}

sealed interface CodeCheck {
    data class Ok(val row: ChallengeRow) : CodeCheck
    data class Wrong(val attemptsLeft: Int) : CodeCheck

    /** 없음 · 만료 · 시도 소진 · 다른 세션 — 한 가지로 */
    data object Gone : CodeCheck
}

/**
 * 6자리 숫자 코드 챌린지 장치 — 가입 확인 · 이메일 변경 · 다시 인증 · 삭제 확인이 같이 쓴다. 코드는 암호학적 난수(`SecureRandom.nextInt(10^6)`, 균등)이고
 * 저장소에는 키 달린 해시만 간다. 한 챌린지의 추측은 [open] 의 `maxAttempts` 번이 전부이고 매번 저장소가 원자적으로 깎는다.
 */
class Challenges(
    private val store: ChallengeStore,
    private val hasher: CodeHasher,
    private val time: TimeProvider,
    /** 코드 · 핸들 · id 의 난수원 — 기본은 SecureRandom. 시험이 값을 정해 넣어 **코드가 `nextInt(10^6)` 그대로**임을 구조로 보인다 */
    private val random: java.util.random.RandomGenerator = SecureRandom(),
) {

    private companion object {
        /** 어떤 입력의 HMAC(64자리 16진수)과도 길이가 달라 [CodeHasher.matches] 가 절대 참이 되지 않는다 */
        const val DEAD_HASH = ""
    }

    fun open(
        purpose: String, subject: String, ttl: Duration, maxAttempts: Int, accountId: String? = null, sessionId: String? = null,
        payload: String? = null, secret: String? = null, ip: String? = null, withHandle: Boolean = false,
        /** false 면 저장하지 않는다 — 한도를 넘은 가입 시도가 같은 모양의 응답을 받되 아무것도 남기지 않게 */
        store: Boolean = true,
        /** true 면 **아무 코드로도 맞지 않는** 줄을 저장한다 — 한도를 넘은 이메일 변경이 같은 모양의 상태(대기 중 · 남은 시도)를 보이되 추측으로도 이길 수 없게 */
        dead: Boolean = false,
    ): Opened {
        val now = time.now()
        val handle = if (withHandle) Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)) else null
        val id = handle?.let(::hashOf) ?: HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val code = "%06d".format(java.util.Locale.ROOT, random.nextInt(1_000_000))
        // 세션 코드는 (용도, 주인) 하나만 열려 있다 — 새 요청이 옛 코드를 닫는다. 가입 시도는 같은 주소에 여럿이 함께 있다 (서로 덮어쓰지 않는다)
        if (store && !withHandle) this.store.deleteBySubject(purpose, subject)
        val row = ChallengeRow(id, purpose, subject, accountId, sessionId, payload, secret, if (dead) DEAD_HASH else hasher.hash(id, code), maxAttempts, 0, now, now.plus(ttl), now, ip)
        if (store) this.store.insert(row)
        return Opened(handle, code, row)
    }

    fun idOf(handle: String): String? = if (handle.length in 20..128 && handle.all { it.isLetterOrDigit() || it == '-' || it == '_' }) hashOf(handle) else null

    fun find(id: String): ChallengeRow? = store.find(id)

    fun findOpen(purpose: String, subject: String): ChallengeRow? = store.findOpen(purpose, subject, time.now())

    fun check(id: String, code: String, sessionId: String? = null): CodeCheck {
        // 세션에 묶인 챌린지는 **시도를 깎기 전에** 세션을 본다 — 같은 계정의 다른 세션(훔친 토큰)이 주인의 시도를 소모하지 못하게
        store.find(id)?.let { if (it.sessionId != null && it.sessionId != sessionId) return CodeCheck.Gone }
        val row = store.spendAttempt(id, time.now()) ?: return CodeCheck.Gone
        if (row.sessionId != null && row.sessionId != sessionId) return CodeCheck.Gone   // 읽은 뒤 다른 챌린지로 바뀐 틈 — 그래도 맞지 않는다
        return if (hasher.matches(row.codeHash, id, code)) CodeCheck.Ok(row) else CodeCheck.Wrong(row.attemptsLeft)
    }

    fun checkOpen(purpose: String, subject: String, code: String, sessionId: String?): CodeCheck {
        val open = findOpen(purpose, subject) ?: return CodeCheck.Gone
        return check(open.id, code, sessionId)
    }

    fun consume(id: String): Boolean = store.delete(id)

    /**
     * [consume] 한 줄을 **되살린다** — 맞는 코드로 시도를 이겼지만 그 뒤 단계가 코드와 무관한 이유(닉네임 충돌)로 막혔을 때, 시도가 닫히지 않게.
     * [row] 는 [check] 가 돌려준 줄(시도 하나가 깎인 뒤의 것) — 그 한 번을 돌려준다. 만료 · 재전송 횟수 · 남은 시도의 나머지는 그대로다.
     */
    fun reopen(row: ChallengeRow) = store.insert(row.copy(attemptsLeft = row.attemptsLeft + 1))

    fun reissue(id: String, ttl: Duration, maxAttempts: Int, cooldown: Duration, maxResends: Int): String? {
        val now = time.now()
        val code = "%06d".format(java.util.Locale.ROOT, random.nextInt(1_000_000))
        return if (store.replaceCode(id, hasher.hash(id, code), now.plus(ttl), now, now.minus(cooldown), maxResends, maxAttempts)) code else null
    }

    fun deleteBySubject(purpose: String, subject: String): Int = store.deleteBySubject(purpose, subject)

    fun deleteForAccount(accountId: String): Int =
        store.deleteByAccount(accountId, listOf(ChallengePurposes.EMAIL_CHANGE, ChallengePurposes.REAUTH, ChallengePurposes.DELETE_CONFIRM))

    fun sweep(retention: Duration): Int = store.purgeExpired(time.now().minus(retention))

    private fun hashOf(handle: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(handle.toByteArray(Charsets.UTF_8)))
}

/** 단일 인스턴스 · 시험용 저장소 (재시작하면 진행 중인 코드가 사라진다 — 보호 환경 가드가 문제로 본다) */
class InMemoryChallengeStore : ChallengeStore {
    private val rows = LinkedHashMap<String, ChallengeRow>()

    @Synchronized override fun insert(row: ChallengeRow) { rows[row.id] = row }

    @Synchronized override fun find(id: String): ChallengeRow? = rows[id]

    @Synchronized override fun findOpen(purpose: String, subject: String, now: Instant): ChallengeRow? =
        rows.values.filter { it.purpose == purpose && it.subject == subject && it.expiresAt.isAfter(now) }.maxByOrNull { it.createdAt }

    @Synchronized override fun spendAttempt(id: String, now: Instant): ChallengeRow? {
        val row = rows[id] ?: return null
        if (row.attemptsLeft <= 0 || !row.expiresAt.isAfter(now)) return null
        return row.copy(attemptsLeft = row.attemptsLeft - 1).also { rows[id] = it }
    }

    @Synchronized override fun delete(id: String): Boolean = rows.remove(id) != null

    @Synchronized override fun deleteBySubject(purpose: String, subject: String): Int {
        val ids = rows.values.filter { it.purpose == purpose && it.subject == subject }.map { it.id }
        ids.forEach { rows.remove(it) }
        return ids.size
    }

    @Synchronized override fun deleteByAccount(accountId: String, purposes: Collection<String>): Int {
        val ids = rows.values.filter { it.accountId == accountId && it.purpose in purposes }.map { it.id }
        ids.forEach { rows.remove(it) }
        return ids.size
    }

    @Synchronized override fun replaceCode(id: String, codeHash: String, expiresAt: Instant, now: Instant, sentBefore: Instant, maxResends: Int, attempts: Int): Boolean {
        val row = rows[id] ?: return false
        if (row.lastSentAt.isAfter(sentBefore) || row.resends >= maxResends) return false
        rows[id] = row.copy(codeHash = codeHash, expiresAt = expiresAt, lastSentAt = now, resends = row.resends + 1, attemptsLeft = attempts)
        return true
    }

    @Synchronized override fun purgeExpired(before: Instant): Int {
        val ids = rows.values.filter { it.expiresAt.isBefore(before) }.map { it.id }
        ids.forEach { rows.remove(it) }
        return ids.size
    }
}
