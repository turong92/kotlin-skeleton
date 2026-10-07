package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.DisplayNames
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import java.time.Duration
import java.time.Instant
import java.util.random.RandomGenerator

val T0: Instant = Instant.parse("2026-10-08T00:00:00Z")

/** 시험용 가짜 저장소 — main 에는 메모리 구현이 없다(재시작에 신원이 사라지면 안 된다). 유니크 규칙은 DB 와 같다: NULL 이 낀 (name_key, tag) 는 겹치지 않고, primary_of 는 계정당 하나 */
class FakeAuthorProfileRepository : AuthorProfileRepository {
    private val rows = LinkedHashMap<String, AuthorProfile>()

    @Synchronized private fun clash(profile: AuthorProfile, ignoring: String? = null): Boolean =
        profile.nameKey != null && profile.tag != null &&
            rows.values.any { it.id != ignoring && it.nameKey == profile.nameKey && it.tag == profile.tag }

    @Synchronized override fun find(id: String): AuthorProfile? = rows[id]

    @Synchronized override fun findAll(ids: Collection<String>): Map<String, AuthorProfile> = ids.mapNotNull { rows[it] }.associateBy { it.id }

    @Synchronized override fun findActive(accountId: String): List<AuthorProfile> =
        rows.values.filter { it.accountId == accountId && it.status == AuthorProfileStatus.ACTIVE }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    @Synchronized override fun countActive(accountId: String): Int = findActive(accountId).size

    @Synchronized override fun findPrimary(accountId: String): AuthorProfile? = rows.values.firstOrNull { it.primaryOf == accountId }

    @Synchronized override fun tagsOf(key: String): Set<String> =
        rows.values.filter { it.nameKey == key && it.tag != null }.map { it.tag!! }.toSet()

    @Synchronized override fun primariesByKey(key: String): List<AuthorProfile> =
        rows.values.filter { it.primaryOf != null && it.nameKey == key && it.status == AuthorProfileStatus.ACTIVE }

    @Synchronized override fun countCreatedSince(accountId: String, since: Instant): Int =
        rows.values.count { it.accountId == accountId && it.primaryOf == null && it.createdAt >= since }

    /** 가짜는 상한 검사와 삽입을 한 락 안에서 한다 — jdbc 는 첫 프로필 줄을 잠근다 */
    @Synchronized override fun insertWithinCap(profile: AuthorProfile, maxActive: Int): CapInsert {
        if (countActive(profile.accountId) >= maxActive) return CapInsert.LIMIT_REACHED
        if (profile.id in rows || clash(profile)) return CapInsert.NAME_TAKEN
        rows[profile.id] = profile
        return CapInsert.INSERTED
    }

    @Synchronized override fun insert(profile: AuthorProfile): Boolean {
        if (profile.id in rows) return false
        if (profile.primaryOf != null && rows.values.any { it.primaryOf == profile.primaryOf }) return false
        if (clash(profile)) return false
        rows[profile.id] = profile
        return true
    }

    @Synchronized override fun rename(id: String, accountId: String, name: String, key: String, tag: String?, now: Instant): RenameResult {
        val row = rows[id]?.takeIf { it.accountId == accountId && it.status == AuthorProfileStatus.ACTIVE } ?: return RenameResult.NOT_FOUND
        val next = row.copy(name = name, nameKey = key, tag = tag, updatedAt = now)
        if (clash(next, ignoring = id)) return RenameResult.TAKEN
        rows[id] = next
        return RenameResult.DONE
    }

    @Synchronized override fun markDeleted(id: String, accountId: String, now: Instant): Boolean {
        val row = rows[id]?.takeIf { it.accountId == accountId && it.status == AuthorProfileStatus.ACTIVE } ?: return false
        rows[id] = row.copy(status = AuthorProfileStatus.DELETED, name = null, nameKey = null, tag = null, deletedAt = now, updatedAt = now)
        return true
    }

    @Synchronized override fun markAdopted(accountId: String, now: Instant): Boolean {
        val row = rows.values.firstOrNull { it.primaryOf == accountId && it.adoptedAt == null } ?: return false
        rows[row.id] = row.copy(adoptedAt = now)
        return true
    }

    @Synchronized override fun deleteByAccount(accountId: String): Int {
        val ids = rows.values.filter { it.accountId == accountId }.map { it.id }
        ids.forEach(rows::remove)
        return ids.size
    }

    @Synchronized fun all(): List<AuthorProfile> = rows.values.toList()
}

/** 정해 둔 순서대로 `nextInt(bound)` 를 돌려주고, 끝나면 고정 시드 난수 — 꼬리표 추첨을 정확히 고르는 시험용 */
class ScriptedRandom(private val script: List<Int> = emptyList(), seed: Long = 7) : RandomGenerator {
    private val fallback = java.util.Random(seed)
    private var next = 0

    override fun nextLong(): Long = fallback.nextLong()

    override fun nextInt(bound: Int): Int = if (next < script.size) script[next++] else fallback.nextInt(bound)
}

/** 서비스 시험 바탕 — 계정은 account 의 메모리 저장소, 프로필은 가짜 저장소 */
class Harness(
    val uniqueness: AccountProperties.DisplayName.Uniqueness = AccountProperties.DisplayName.Uniqueness.TAGGED,
    val properties: AuthorProfileProperties = AuthorProfileProperties(),
    reserved: List<String> = emptyList(),
    changeLimit: Int = 10,
    random: RandomGenerator = ScriptedRandom(),
    /** true 면 처음 보는 계정 id 를 이름 없는 ACTIVE 계정으로 만든다 — [AuthorProfileService.create] 가 첫 프로필을 먼저 보장하므로 계정이 있어야 한다 */
    autoAccounts: Boolean = true,
    val adoptionListeners: MutableList<dev.sumin.skeleton.common.author.AuthorAdoptionListener> = mutableListOf(),
) {
    private val backing = InMemoryAccountRepository()
    val accounts: dev.sumin.skeleton.account.AccountRepository = object : dev.sumin.skeleton.account.AccountRepository by backing {
        override fun findById(id: String): Account? = backing.findById(id) ?: if (autoAccounts) bare(id) else null
    }
    val repository = FakeAuthorProfileRepository()
    val time = TimeProvider.fixed(T0)
    val names = DisplayNames(
        AccountProperties.DisplayName(uniqueness = uniqueness, reserved = reserved, changeLimit = changeLimit, changeWindow = Duration.ofDays(1)),
        random,
    )
    private val store = InMemoryFixedWindowRateLimitStore()
    val limits = AccountRateLimits({ store }, time)
    val service = AuthorProfileService(repository, { accounts }, names, limits, properties, time) { adoptionListeners.toList() }

    /** 계정을 만든다. [name] 이 있으면 닉네임 · 비교용 키 · [tag] 까지 저장한 상태로 */
    fun account(id: String, name: String? = null, tag: String? = null, status: AccountStatus = AccountStatus.ACTIVE): Account {
        val account = Account(
            id = id, email = "$id@example.com", emailVerified = true, status = status, roles = setOf("USER"),
            displayName = name, locale = null, timeZone = null, createdAt = T0, updatedAt = T0,
            displayNameKey = name?.let(names::key), displayTag = tag,
        )
        check(backing.insert(account, emptyList())) { "account $id was not stored" }
        return account
    }

    private fun bare(id: String): Account? =
        Account(id, "$id@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, T0, T0).takeIf { backing.insert(it, emptyList()) } ?: backing.findById(id)

    /** 사용자가 만든 살아 있는 프로필만 (첫 프로필 제외) */
    fun made(account: String) = service.list(account).filter { it.primaryOf == null }

    fun failure(block: () -> Unit): AuthorProfileException = kotlin.test.assertFailsWith<AuthorProfileException> { block() }
}
