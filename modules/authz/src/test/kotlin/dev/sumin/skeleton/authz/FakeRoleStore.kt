package dev.sumin.skeleton.authz

import java.util.concurrent.ConcurrentHashMap

/** 메모리 저장소 — 보호 역할을 건드리지 않고, 같은 이름을 동시에 넣어도 한 쪽만 true */
class FakeRoleStore : AuthzRoleStore {
    val rows: MutableMap<String, StoredRole> = ConcurrentHashMap()

    override fun list(): List<StoredRole> = rows.values.toList()
    override fun find(name: String): StoredRole? = rows[name]
    override fun insert(role: StoredRole): Boolean = rows.putIfAbsent(role.name, role) == null
    override fun update(name: String, description: String?, permissions: Set<String>, includes: Set<String>?): Boolean {
        var changed = false
        rows.computeIfPresent(name) { _, old -> if (old.protected) old else { changed = true; old.copy(description = description, permissions = permissions, includes = includes ?: old.includes) } }
        return changed
    }

    override fun delete(name: String): Boolean {
        var removed = false
        rows.computeIfPresent(name) { _, old -> if (old.protected) old else { removed = true; null } }
        return removed
    }
}
