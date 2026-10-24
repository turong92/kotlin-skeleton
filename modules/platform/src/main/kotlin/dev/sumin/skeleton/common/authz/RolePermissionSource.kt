package dev.sumin.skeleton.common.authz

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 역할 → 권한 매핑. 기본은 yml([YmlRolePermissionSource]), `authz-jdbc` 가 DB 로 교체한다 */
interface RolePermissionSource {
    /** 매핑에 없는 역할은 빈 집합 — 오류 아님 */
    fun permissionsOf(role: String): Set<String>

    /** 정의된 역할 이름(매핑의 키) */
    fun definedRoles(): Set<String>
}

class YmlRolePermissionSource(private val roles: Map<String, Set<String>>) : RolePermissionSource {
    override fun permissionsOf(role: String): Set<String> = roles[role].orEmpty()
    override fun definedRoles(): Set<String> = roles.keys
}

/**
 * 역할 → 권한만 프로세스 메모리에 담는 감싸개(`authz-jdbc` 용). [ttl] 이 지나면 다시 읽고, [invalidate] 는 같은 인스턴스의 변경을 즉시 반영한다.
 * `ttl` 이 0 이면 캐시를 끈다.
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
        // 읽는 사이 무효화가 지나갔으면 옛 값을 다시 넣지 않는다
        if (generation.get() == started) entries[role] = Entry(read, now.plus(ttl))
        return read
    }

    override fun definedRoles(): Set<String> = delegate.definedRoles()

    fun invalidate() {
        generation.incrementAndGet()
        entries.clear()
    }
}
