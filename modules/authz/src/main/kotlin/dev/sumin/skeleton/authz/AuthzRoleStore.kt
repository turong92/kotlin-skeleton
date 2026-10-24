package dev.sumin.skeleton.authz

/** 저장된 역할. [permissions] 의 `*` 는 전권 역할(`skeleton.authz.super-role`)에만 있다 */
data class StoredRole(
    val name: String,
    val description: String?,
    val permissions: Set<String>,
    val protected: Boolean = false,
)

/**
 * 역할 → 권한 표의 저장소 포트(`authz-jdbc` 가 구현한다). 안전장치는 [RoleService] 가 먼저 막고,
 * [update] · [delete] 는 저장소도 보호 역할을 건드리지 않는다(동시 호출에도 같은 줄이 바뀌지 않게).
 */
interface AuthzRoleStore {
    fun list(): List<StoredRole>

    fun find(name: String): StoredRole?

    /** 같은 이름이 이미 있으면 false (동시에 만들면 정확히 한 쪽만 true) */
    fun insert(role: StoredRole): Boolean

    /** 없거나 보호 역할이면 false */
    fun update(name: String, description: String?, permissions: Set<String>): Boolean

    /** 없거나 보호 역할이면 false */
    fun delete(name: String): Boolean
}
