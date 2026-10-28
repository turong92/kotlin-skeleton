package dev.sumin.skeleton.common.authz

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 역할 → 권한 매핑. 기본은 yml([YmlRolePermissionSource]), `authz-jdbc` 가 DB 로 교체한다 */
interface RolePermissionSource {
    /**
     * 역할의 권한 — **물려받은 것까지 펼친 값**([includesOf] 를 [RoleClosure] 로 따라간 모든 역할의 권한). 모든 판정이 이 한 곳을 읽으므로 위계를 따로 알 필요가 없다.
     * 매핑에 없는 역할은 빈 집합 — 오류 아님
     */
    fun permissionsOf(role: String): Set<String>

    /** 이 역할이 직접 물려받는 역할(펼치지 않은 한 걸음). 위계가 없는 구현은 빈 집합 */
    fun includesOf(role: String): Set<String> = emptySet()

    /** 정의된 역할 이름(매핑의 키) */
    fun definedRoles(): Set<String>

    /**
     * 역할을 **계정에 주기 직전**, 주는 쪽의 열린 트랜잭션 안에서 부른다. 표가 있는 구현은 그 역할 줄을 공유 잠금으로 읽어 돌려준다 —
     * 역할을 지우는 쪽(줄을 배타 잠금으로 잡는다)과 줄 세워서, 지운 역할이 계정에 남지 않게 한다. 줄이 없으면 false(이미 지워졌다).
     * 표가 없는 구현(yml)은 null — 판단하지 않는다.
     */
    fun lockForGrant(role: String): Boolean? = null
}

/** [roles] 는 역할이 **직접** 가진 권한, [includes] 는 역할 → 물려받는 역할. [permissionsOf] 가 펼친 값을 돌려준다 */
class YmlRolePermissionSource(
    private val roles: Map<String, Set<String>>,
    private val includes: Map<String, Set<String>> = emptyMap(),
) : RolePermissionSource {
    override fun permissionsOf(role: String): Set<String> =
        RoleClosure.expand(setOf(role), ::includesOf).flatMapTo(sortedSetOf()) { ownPermissionsOf(it) }

    override fun includesOf(role: String): Set<String> = includes[role].orEmpty()

    /** 펼치지 않은, 이 역할이 직접 가진 권한 — 첫 기동 씨앗이 표에 옮길 때 쓴다 */
    fun ownPermissionsOf(role: String): Set<String> = roles[role].orEmpty()

    override fun definedRoles(): Set<String> = roles.keys
}

/**
 * 역할 → 권한만 프로세스 메모리에 담는 감싸개(`authz-jdbc` 용). [ttl] 이 지나면 다시 읽고, [invalidate] 는 같은 인스턴스의 변경을 즉시 반영한다.
 * `ttl` 이 0 이면 캐시를 끈다. 담는 값은 펼친 권한이라 아래 역할이 바뀌면 위 역할의 값도 바뀐다 — [invalidate] 는 늘 전체를 비운다.
 */
class CachingRolePermissionSource(
    private val delegate: RolePermissionSource,
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : RolePermissionSource {
    private class Entry(val permissions: Set<String>, val expiresAt: Instant)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val generation = java.util.concurrent.atomic.AtomicLong()

    override fun permissionsOf(role: String): Set<String> {
        if (ttl.isZero || ttl.isNegative) return delegate.permissionsOf(role)
        val now = clock.instant()
        entries[role]?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.permissions }
        val started = generation.get()
        val read = delegate.permissionsOf(role)
        // 읽는 사이 무효화가 지나갔으면 옛 값을 다시 넣지 않는다 — 넣은 뒤에 한 번 더 보고, 그 사이 지나갔으면 방금 넣은 것을 거둔다
        if (generation.get() == started) {
            val entry = Entry(read, now.plus(ttl))
            entries[role] = entry
            if (generation.get() != started) entries.remove(role, entry)
        }
        return read
    }

    override fun includesOf(role: String): Set<String> = delegate.includesOf(role)

    override fun definedRoles(): Set<String> = delegate.definedRoles()

    override fun lockForGrant(role: String): Boolean? = delegate.lockForGrant(role)

    fun invalidate() {
        generation.incrementAndGet()
        entries.clear()
    }
}
