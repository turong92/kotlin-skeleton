package dev.sumin.skeleton.authz.jdbc

import dev.sumin.skeleton.authz.AuthzRoleStore
import dev.sumin.skeleton.authz.StoredRole
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * `authz_roles` · `authz_role_permissions`. 쓰기는 한 트랜잭션 안에서(호출자의 트랜잭션이 있으면 거기에 합류) 하고,
 * [onChange](역할 → 권한 캐시 비우기)는 커밋 뒤에 부른다 — 커밋 전에 비우면 다른 요청이 옛 값을 다시 캐시에 올릴 수 있다.
 * 보호 역할(`protected`)은 [update] · [delete] 문장 자체가 건드리지 않는다.
 */
class JdbcAuthzRoleStore(
    private val jdbc: JdbcClient,
    private val dialect: SqlDialect,
    private val tx: TransactionOperations,
    private val time: TimeProvider,
    private val onChange: () -> Unit,
) : AuthzRoleStore {
    override fun list(): List<StoredRole> {
        val permissions = jdbc.sql("select role, permission from authz_role_permissions")
            .query { rs, _ -> rs.getString("role") to rs.getString("permission") }.list()
            .groupBy({ it.first }, { it.second })
        return jdbc.sql("select name, description, protected from authz_roles order by name")
            .query { rs, _ -> StoredRole(rs.getString("name"), rs.getString("description"), emptySet(), rs.getBoolean("protected")) }.list()
            .map { it.copy(permissions = permissions[it.name].orEmpty().toSortedSet()) }
    }

    override fun find(name: String): StoredRole? = read(name, lock = false)

    /** 두 방언 모두 `for update` 가 최신 커밋을 읽는다 — 권한 줄도 잠가 읽어 MySQL 의 반복 읽기 스냅샷에 옛 값이 남지 않게 한다 */
    override fun findForUpdate(name: String): StoredRole? = read(name, lock = true)

    private fun read(name: String, lock: Boolean): StoredRole? {
        val suffix = if (lock) " for update" else ""
        return jdbc.sql("select name, description, protected from authz_roles where name = :name$suffix").param("name", name)
            .query { rs, _ -> StoredRole(rs.getString("name"), rs.getString("description"), emptySet(), rs.getBoolean("protected")) }.optional().orElse(null)
            ?.let { it.copy(permissions = permissionsOf(it.name, suffix)) }
    }

    /**
     * 같은 이름이 이미 있으면 false. 유니크 위반은 트랜잭션 밖으로 나온 뒤에 잡는다 — PostgreSQL 은 위반한 트랜잭션을 더 쓰지 못하므로
     * 호출자(서비스)는 false 를 받으면 예외를 던져 바깥 트랜잭션을 되돌린다.
     */
    override fun insert(role: StoredRole): Boolean = try {
        tx.execute {
            val now = dialect.instantParam(time.now())
            jdbc.sql("insert into authz_roles (name, description, protected, created_at, updated_at) values (:name, :description, :protected, :at, :at)")
                .param("name", role.name).param("description", role.description, java.sql.Types.VARCHAR).param("protected", role.protected).param("at", now).update()
            insertPermissions(role.name, role.permissions)
            changed()
            true
        }!!
    } catch (e: DuplicateKeyException) {
        false
    }

    override fun update(name: String, description: String?, permissions: Set<String>): Boolean = tx.execute {
        val rows = jdbc.sql("update authz_roles set description = :description, updated_at = :at where name = :name and protected = false")
            .param("description", description, java.sql.Types.VARCHAR).param("at", dialect.instantParam(time.now())).param("name", name).update()
        if (rows == 0) return@execute false
        jdbc.sql("delete from authz_role_permissions where role = :name").param("name", name).update()
        insertPermissions(name, permissions)
        changed()
        true
    }!!

    override fun delete(name: String): Boolean = tx.execute {
        val rows = jdbc.sql("delete from authz_roles where name = :name and protected = false").param("name", name).update()
        if (rows == 0) return@execute false
        changed()
        true
    }!!

    private fun permissionsOf(role: String, suffix: String): Set<String> =
        jdbc.sql("select permission from authz_role_permissions where role = :role order by permission$suffix").param("role", role)
            .query { rs, _ -> rs.getString("permission") }.list().toSortedSet<String>()

    private fun insertPermissions(role: String, permissions: Set<String>) =
        permissions.forEach {
            jdbc.sql("insert into authz_role_permissions (role, permission) values (:role, :permission)").param("role", role).param("permission", it).update()
        }

    private fun changed() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = onChange()
            })
        } else {
            onChange()
        }
    }
}
