package dev.sumin.skeleton.authz

/**
 * 저장된 역할. [permissions] 는 직접 가진 권한(물려받은 것은 안 든다)이고 `*` 는 전권 역할(`skeleton.authz.super-role`)에만 있다.
 * [includes] 는 이 역할이 물려받는 역할 이름 — 전권 역할은 갖지도 받지도 않는다
 */
data class StoredRole(
    val name: String,
    val description: String?,
    val permissions: Set<String>,
    val protected: Boolean = false,
    val includes: Set<String> = emptySet(),
)

/**
 * 역할 → 권한 표의 저장소 포트(`authz-jdbc` 가 구현한다). 안전장치는 [RoleService] 가 먼저 막고,
 * [update] · [delete] 는 저장소도 보호 역할을 건드리지 않는다(동시 호출에도 같은 줄이 바뀌지 않게).
 */
interface AuthzRoleStore {
    fun list(): List<StoredRole>

    fun find(name: String): StoredRole?

    /**
     * 열린 트랜잭션 안에서 역할 줄을 잠그고 읽는다(`select … for update`) — 고치기 · 지우기가 「지금 저장된 목록」을 보고 판정한 뒤 쓰는 사이에
     * 다른 변경이 끼어들지 못하게 한다. 잠금이 없는 저장소(메모리)는 [find] 와 같다.
     */
    fun findForUpdate(name: String): StoredRole? = find(name)

    /**
     * 위계(`includes`)를 바꾸는 편집이 열린 트랜잭션의 **첫 문장**으로 부른다 — 늘 있고 고정인 전권 역할 줄 하나를 `select … for update` 로 잡아, 여러 인스턴스가
     * 동시에 A→B · B→A 를 넣어 순환이 생기는 일을 줄 세운다. 잡은 뒤에야 첫 일반 읽기가 나가야 MySQL 의 반복 읽기 스냅샷이 앞선 편집의 커밋을 본다.
     * 잠금이 없는 저장소(메모리)는 [RoleService] 의 프로세스 잠금으로 충분하다.
     */
    fun lockHierarchy() {}

    /** [name] 을 `includes` 로 물려받는 역할들 */
    fun includedBy(name: String): Set<String> = list().filter { name in it.includes }.mapTo(HashSet()) { it.name }

    /** 같은 이름이 이미 있으면 false (동시에 만들면 정확히 한 쪽만 true) */
    fun insert(role: StoredRole): Boolean

    /** 없거나 보호 역할이면 false. [includes] 가 null 이면 물려받는 역할은 그대로 둔다 */
    fun update(name: String, description: String?, permissions: Set<String>, includes: Set<String>? = null): Boolean

    /** 없거나 보호 역할이면 false */
    fun delete(name: String): Boolean
}
