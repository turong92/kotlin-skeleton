package dev.sumin.skeleton.authz

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.RolePermissionSource
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionOperations

/** 잠금이 없는 저장소(메모리)에서도 같은 역할 · 마지막 `authz.roles` 를 두 요청이 동시에 깎지 못한다 */
class RoleServiceConcurrencyTest {
    private val store = FakeRoleStore().apply {
        insert(StoredRole("ADMIN", null, setOf("*"), protected = true))
        insert(StoredRole("R1", null, setOf(AuthzPermissions.ROLES)))
        insert(StoredRole("R2", null, setOf(AuthzPermissions.ROLES)))
    }
    private val rendezvous = CyclicBarrier(2)

    // 자기 잠금 판정이 다른 역할을 읽는 순간 두 요청을 만나게 해서, 잠금이 없으면 둘 다 「남는 역할이 있다」고 본다
    private val source = object : RolePermissionSource {
        override fun permissionsOf(role: String): Set<String> {
            val seen = store.find(role)?.permissions ?: emptySet()
            try { rendezvous.await(300, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { rendezvous.reset() }
            return seen
        }
        override fun definedRoles(): Set<String> = store.list().map { it.name }.toSet()
    }
    private val access = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = setOf("*")
    }
    private val service = RoleService(
        store, source, access, { emptyList() }, null, OperatorAuditLog { }, "ADMIN", TransactionOperations.withoutTransaction(),
    )
    private val actorA = OperatorRef("a", setOf("R1", "R2"))
    private val actorB = OperatorRef("b", setOf("R1", "R2"))

    @Test
    fun `two operators deleting the two roles that grant role management cannot both succeed`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(actorA to "R1", actorB to "R2").map { (actor, role) ->
                pool.submit<Throwable?> { runCatching { service.delete(actor, role) }.exceptionOrNull() }
            }.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(results.count { it == null }).isEqualTo(1)
            assertThat(results.filterNotNull().single()).isInstanceOfSatisfying(AuthzRoleException::class.java) {
                assertThat(it.errorCode.code).isEqualTo("AUTHZ.SELF_LOCKOUT")
            }
            assertThat(store.list().count { AuthzPermissions.ROLES in it.permissions }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }
}
