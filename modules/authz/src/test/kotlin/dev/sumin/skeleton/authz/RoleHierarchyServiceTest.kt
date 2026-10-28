package dev.sumin.skeleton.authz

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RoleClosure
import dev.sumin.skeleton.common.authz.RolePermissionSource
import dev.sumin.skeleton.common.authz.TokenOperatorAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.transaction.support.TransactionOperations

/** 역할 위계(includes)의 안전장치 — 순환 · 없는 역할 · 전권 · 상승 · 자기 잠금 · 물려받히는 역할 삭제. 저장소는 메모리, 판정은 저장소의 위계를 그대로 따른다 */
class RoleHierarchyServiceTest {
    private val store = FakeRoleStore().apply {
        insert(StoredRole("ADMIN", null, setOf("*"), protected = true))
        insert(StoredRole("OPS", null, setOf(AuthzPermissions.ROLES)))
        insert(StoredRole("CS", null, setOf("payment.read")))
        insert(StoredRole("REFUNDER", null, setOf("payment.refund")))
        insert(StoredRole("STAFF", null, setOf("payment.read"), includes = setOf("OPS")))
    }
    private val audit = object : OperatorAuditLog {
        val actions = mutableListOf<OperatorAction>()
        override fun record(action: OperatorAction) { actions += action }
    }
    private val source = object : RolePermissionSource {
        override fun permissionsOf(role: String): Set<String> =
            RoleClosure.expand(setOf(role), ::includesOf).flatMapTo(HashSet()) { store.find(it)?.permissions.orEmpty() }
        override fun includesOf(role: String): Set<String> = store.find(role)?.includes.orEmpty()
        override fun definedRoles(): Set<String> = store.list().map { it.name }.toSet()
    }
    private val declarations = listOf(
        PermissionDeclaration("payment", listOf(DeclaredPermission("payment.read"), DeclaredPermission("payment.refund"))),
        PermissionDeclaration("authz", listOf(DeclaredPermission(AuthzPermissions.ROLES))),
    )
    private val service = RoleService(
        store, source, TokenOperatorAccess(source), { declarations }, null, audit, "ADMIN", TransactionOperations.withoutTransaction(),
    )
    private val admin = OperatorRef("a", setOf("ADMIN"))
    private val ops = OperatorRef("o", setOf("OPS"))
    private val staff = OperatorRef("s", setOf("STAFF"))

    private fun codeOf(block: () -> Unit) = assertFailsWith<AuthzRoleException> { block() }.errorCode.code

    @Test
    fun `roles that would include each other in a loop are a 409 ROLE_CYCLE`() {
        service.update(admin, "CS", null, listOf("payment.read"), listOf("REFUNDER"))
        assertEquals("AUTHZ.ROLE_CYCLE", codeOf { service.update(admin, "REFUNDER", null, listOf("payment.refund"), listOf("CS")) })
        assertEquals("AUTHZ.ROLE_CYCLE", codeOf { service.update(admin, "CS", null, listOf("payment.read"), listOf("CS")) })
        assertEquals(emptySet(), store.find("REFUNDER")!!.includes)
    }

    @Test
    fun `including a role that does not exist is a 400 INCLUDE_UNKNOWN`() {
        assertEquals("AUTHZ.INCLUDE_UNKNOWN", codeOf { service.update(admin, "CS", null, listOf("payment.read"), listOf("GHOST")) })
        assertEquals("AUTHZ.INCLUDE_UNKNOWN", codeOf { service.create(admin, "NEWROLE", null, emptyList(), listOf("GHOST")) })
    }

    @Test
    fun `the full access role cannot be inherited`() {
        assertEquals("AUTHZ.ROLE_PROTECTED", codeOf { service.update(admin, "CS", null, listOf("payment.read"), listOf("ADMIN")) })
        assertEquals("AUTHZ.ROLE_PROTECTED", codeOf { service.create(admin, "NEWROLE", null, emptyList(), listOf("ADMIN")) })
    }

    @Test
    fun `an operator holding only authz roles cannot hand out a permission by including a role that has it`() {
        assertEquals("AUTHZ.ESCALATION", codeOf { service.update(ops, "CS", null, listOf("payment.read"), listOf("REFUNDER")) })
        assertEquals("AUTHZ.ESCALATION", codeOf { service.create(ops, "NEWROLE", null, emptyList(), listOf("REFUNDER")) })
        assertEquals(emptySet(), store.find("CS")!!.includes)
        // 이미 걸린 REFUNDER 를 빼는 것도 같은 검사 — 자기 권한 밖 권한을 거두지 못한다
        service.update(admin, "CS", null, listOf("payment.read"), listOf("REFUNDER"))
        assertEquals("AUTHZ.ESCALATION", codeOf { service.update(ops, "CS", null, listOf("payment.read"), emptyList()) })
    }

    @Test
    fun `a change that takes away the authz roles an operator inherits is a 409 SELF_LOCKOUT`() {
        // staff 는 STAFF 만 갖고, authz.roles 는 OPS 에서 물려받는다
        assertEquals("AUTHZ.SELF_LOCKOUT", codeOf { service.update(staff, "STAFF", null, listOf("payment.read"), emptyList()) })
        assertEquals("AUTHZ.SELF_LOCKOUT", codeOf { service.update(staff, "OPS", null, listOf("payment.read")) })
        assertEquals("AUTHZ.SELF_LOCKOUT", codeOf { service.delete(staff, "STAFF") })
        assertEquals(setOf("OPS"), store.find("STAFF")!!.includes)
        assertEquals(setOf(AuthzPermissions.ROLES), store.find("OPS")!!.permissions)
    }

    @Test
    fun `a role that another role includes cannot be deleted until it is removed there`() {
        assertEquals("AUTHZ.ROLE_INCLUDED", codeOf { service.delete(admin, "OPS") })
        service.update(admin, "STAFF", null, listOf("payment.read"), emptyList())
        service.delete(admin, "OPS")
        assertEquals(null, store.find("OPS"))
    }

    @Test
    fun `an update without includes keeps them, and an empty list clears them`() {
        service.update(admin, "STAFF", "renamed", listOf("payment.read"))
        assertEquals(setOf("OPS"), store.find("STAFF")!!.includes)
        service.update(admin, "STAFF", "renamed", listOf("payment.read"), emptyList())
        assertEquals(emptySet(), store.find("STAFF")!!.includes)
    }

    @Test
    fun `the role view lists what it includes and the permissions it ends up with`() {
        val staffRole = service.list().single { it.name == "STAFF" }
        assertEquals(listOf("OPS"), staffRole.includes)
        assertEquals(listOf("payment.read"), staffRole.permissions)
        assertEquals(listOf(AuthzPermissions.ROLES, "payment.read"), staffRole.effectivePermissions)
        val created = service.create(admin, "LEAD", null, listOf("payment.refund"), listOf("STAFF"))
        assertEquals(listOf(AuthzPermissions.ROLES, "payment.read", "payment.refund"), created.effectivePermissions)
    }

    @Test
    fun `the operator log records the includes added and removed`() {
        service.update(admin, "CS", null, listOf("payment.read"), listOf("REFUNDER"))
        service.update(admin, "CS", null, listOf("payment.read"), listOf("OPS"))
        service.create(admin, "LEAD", null, emptyList(), listOf("CS"))
        assertEquals(mapOf("added" to "", "removed" to "", "includesAdded" to "REFUNDER", "includesRemoved" to ""), audit.actions[0].detail)
        assertEquals(mapOf("added" to "", "removed" to "", "includesAdded" to "OPS", "includesRemoved" to "REFUNDER"), audit.actions[1].detail)
        assertEquals(mapOf("includes" to "CS"), audit.actions[2].detail)
    }
}
