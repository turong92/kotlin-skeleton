package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 「이 계정은 2단계가 필수인데 아직 등록 전인가」 - 필수 문(인터셉터)이 요청마다 묻는다. 계정 한 줄(기본키) + 등록 상태 한 번을 읽고 [ttl](30초) 동안 기억한다.
 * 등록 · 끄기 · 운영 해제 · 탈퇴는 [invalidate] 로 바로 지운다 - 등록하면 30초를 기다리지 않고 풀리고, 운영 해제는 바로 다시 걸린다.
 * (정책을 바꾸는 쪽 - 앱이 역할을 주거나 뺀 경우 - 은 최대 [ttl] 뒤에 반영된다.)
 */
class MfaRequirement(
    private val accounts: AccountRepository,
    private val store: MfaStore,
    private val policy: MfaPolicy,
    private val time: TimeProvider,
    private val ttl: Duration = Duration.ofSeconds(30),
) {
    private class Entry(val missing: Boolean, val until: Instant)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun enrollmentMissing(accountId: String): Boolean {
        val now = time.now()
        cache[accountId]?.takeIf { now.isBefore(it.until) }?.let { return it.missing }
        val account = accounts.findById(accountId)
        val missing = account != null && policy.required(account) && store.find(accountId)?.enabled != true
        if (cache.size >= MAX_ENTRIES) cache.entries.removeIf { !now.isBefore(it.value.until) }
        if (cache.size < MAX_ENTRIES) cache[accountId] = Entry(missing, now.plus(ttl))
        return missing
    }

    fun invalidate(accountId: String) {
        cache.remove(accountId)
    }

    private companion object {
        const val MAX_ENTRIES = 10_000
    }
}
