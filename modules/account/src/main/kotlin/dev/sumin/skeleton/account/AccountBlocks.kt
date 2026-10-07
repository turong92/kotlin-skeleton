package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 재가입 차단 한 줄 — 운영자가 정지한 계정을 지울 때 남기는 **되돌릴 수 없는 해시**다. 이메일 · 제공자 주체 원문은 어디에도 없다.
 * [kind]: [AccountBlocks.EMAIL] | [AccountBlocks.IDENTITY]. [accountId] 는 지워진(ERASED) 계정 행을 가리킨다 — 감사용.
 */
data class AccountBlock(
    val id: Long,
    val kind: String,
    val hash: String,
    val reason: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val createdBy: String?,
    val accountId: String?,
) {
    // 해시도 화면 · 로그에 내지 않는다
    override fun toString() = "AccountBlock(id=$id, kind=$kind, reason=$reason, createdAt=$createdAt, expiresAt=$expiresAt, createdBy=$createdBy, accountId=$accountId)"
}

data class AccountBlockPage(val items: List<AccountBlock>, val total: Long)

/** 차단 저장소 포트 — `account-jdbc` 가 `account_blocks` 표로 구현하고, 메모리 구현이 로컬 · 시험 기본이다. [add] 는 같은 해시가 이미 있으면 아무것도 하지 않는다(멱등) */
interface AccountBlockStore {
    fun add(kind: String, hash: String, reason: String?, now: Instant, expiresAt: Instant?, createdBy: String?, accountId: String?)

    /** [hashes] 중 하나라도 [now] 에 유효한(만료되지 않은) 차단이 있으면 true */
    fun anyActive(hashes: Collection<String>, now: Instant): Boolean

    fun list(page: Int, size: Int): AccountBlockPage

    fun remove(id: Long): Boolean

    /** 만료된 줄을 지운다 */
    fun sweepExpired(now: Instant): Int
}

class InMemoryAccountBlockStore : AccountBlockStore {
    private val rows = LinkedHashMap<Long, AccountBlock>()
    private var next = 1L

    @Synchronized override fun add(kind: String, hash: String, reason: String?, now: Instant, expiresAt: Instant?, createdBy: String?, accountId: String?) {
        if (rows.values.any { it.hash == hash }) return
        rows[next] = AccountBlock(next, kind, hash, reason, now, expiresAt, createdBy, accountId); next++
    }

    @Synchronized override fun anyActive(hashes: Collection<String>, now: Instant) =
        rows.values.any { it.hash in hashes && (it.expiresAt == null || it.expiresAt.isAfter(now)) }

    @Synchronized override fun list(page: Int, size: Int): AccountBlockPage {
        val all = rows.values.sortedByDescending { it.id }
        return AccountBlockPage(all.drop(page * size).take(size), all.size.toLong())
    }

    @Synchronized override fun remove(id: Long) = rows.remove(id) != null

    @Synchronized override fun sweepExpired(now: Instant) = rows.values.count { it.expiresAt != null && !it.expiresAt.isAfter(now) }.also {
        rows.values.removeIf { r -> r.expiresAt != null && !r.expiresAt.isAfter(now) }
    }
}

/**
 * 재가입 차단 — 서버 비밀([key])로 만든 HMAC-SHA256 해시만 저장한다 (비밀 없이는 후보 주소를 대입해 볼 수도 없다). 해시 대상은 정규화된 이메일과
 * (이메일이 주체가 아닌) 로그인 수단의 `method:subject`. 차단은 **메일함 · 제공자 계정을 증명한 사람에게만** 드러난다 — 가입 요청 응답은 그대로다.
 */
class AccountBlocks(
    private val store: AccountBlockStore,
    private val key: ByteArray,
    private val retention: Duration,
    private val time: TimeProvider,
) {
    fun emailHash(email: String): String = hmac("email:" + Emails.normalize(email))

    fun identityHash(method: String, subject: String): String =
        if (method in EMAIL_METHODS) emailHash(subject) else hmac("identity:$method:$subject")

    /** 이 이메일 · 로그인 수단으로 새 계정을 만들면 안 되나 */
    fun blocked(email: String?, method: String? = null, subject: String? = null): Boolean {
        val hashes = listOfNotNull(email?.let(::emailHash), if (method != null && subject != null) identityHash(method, subject) else null)
        return hashes.isNotEmpty() && store.anyActive(hashes, time.now())
    }

    /** 지우기 **전에** 부른다 — 계정의 이메일과 모든 로그인 수단의 해시를 남긴다 (같은 해시는 한 번만) */
    fun add(account: Account, identities: List<Identity>, reason: String?, createdBy: String?) {
        val now = time.now()
        val expires = retention.takeIf { !it.isZero && !it.isNegative }?.let { now.plus(it) }
        val hashes = linkedMapOf<String, String>()   // hash -> kind
        account.email?.let { hashes[emailHash(it)] = EMAIL }
        identities.forEach { hashes[identityHash(it.method, it.subject)] = if (it.method in EMAIL_METHODS) EMAIL else IDENTITY }
        hashes.forEach { (hash, kind) -> store.add(kind, hash, reason?.take(200), now, expires, createdBy, account.id) }
    }

    fun list(page: Int, size: Int) = store.list(page.coerceAtLeast(0), size.coerceIn(1, 100))

    fun remove(id: Long) = store.remove(id)

    fun sweep(): Int = store.sweepExpired(time.now())

    private fun hmac(text: String): String =
        HexFormat.of().formatHex(Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray(Charsets.UTF_8)))

    companion object {
        const val EMAIL = "email"
        const val IDENTITY = "identity"
        private val EMAIL_METHODS = setOf(SignInMethods.PASSWORD, SignInMethods.MAGIC_LINK)

        /** 로컬 · 시험용 — 메모리 저장소와 **인스턴스마다 무작위인 키** (코드에 박힌 키는 없다; 메모리 저장소는 재시작하면 비므로 키가 재시작을 넘길 이유도 없다) */
        fun local(time: TimeProvider, retention: Duration = Duration.ZERO) =
            AccountBlocks(InMemoryAccountBlockStore(), ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }, retention, time)
    }
}
