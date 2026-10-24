package dev.sumin.skeleton.account.authz

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 부여: 정의된 역할만, 자기 권한 밖 역할은 못 준다. 마지막 전권은 super-role 이름을 따른다 */
class AccountRoleGrantRulesTest {
    private val h = AccountHarness(rolePermissions = mapOf(
        "ROLER" to setOf("account.roles"),
        "VIEWER" to setOf("account.read"),
        "MODERATOR" to setOf("board.moderate"),
    ))
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code
    private fun roles(id: String) = h.repo.findById(id)!!.roles

    @Test
    fun `a role nobody defined is 400 ROLE_UNKNOWN - default roles count as defined`() {
        val target = h.activeAccount("t@example.com")
        assertEquals("ACCOUNT.ROLE_UNKNOWN", code { h.admin.grantRole("acc_x", target.id, "GHOST", setOf("*")) })
        h.admin.grantRole("acc_x", target.id, "USER", setOf("*"))
        h.admin.grantRole("acc_x", target.id, "VIEWER", setOf("*"))
        assertTrue("VIEWER" in roles(target.id))
    }

    @Test
    fun `a role holding permissions the caller lacks is 403 ROLE_ESCALATION - one inside the callers permissions passes`() {
        val target = h.activeAccount("t@example.com")
        val callerPermissions = setOf("account.roles")
        assertEquals("ACCOUNT.ROLE_ESCALATION", code { h.admin.grantRole("acc_x", target.id, "ADMIN", callerPermissions) })
        assertEquals("ACCOUNT.ROLE_ESCALATION", code { h.admin.grantRole("acc_x", target.id, "VIEWER", callerPermissions) })
        assertEquals("ACCOUNT.ROLE_ESCALATION", code { h.admin.grantRole("acc_x", target.id, "MODERATOR", callerPermissions) })
        assertTrue("ADMIN" !in roles(target.id))
        h.admin.grantRole("acc_x", target.id, "ROLER", callerPermissions)
        h.admin.grantRole("acc_x", target.id, "MODERATOR", callerPermissions + "board.moderate")
        assertTrue(roles(target.id).containsAll(setOf("ROLER", "MODERATOR")))
    }

    @Test
    fun `full authority may grant anything defined`() {
        val target = h.activeAccount("t@example.com")
        h.admin.grantRole("acc_x", target.id, "ADMIN", setOf("*"))
        assertTrue("ADMIN" in roles(target.id))
    }

    @Test
    fun `the last holder of a renamed super role cannot be revoked, suspended or deleted`() {
        val o = AccountHarness(superRole = "OWNER")
        val owner = o.activeAccount("owner@example.com")
        o.repo.grantRole(owner.id, "OWNER", o.time.now())
        val other = o.activeAccount("other@example.com")
        assertEquals("ACCOUNT.LAST_ADMIN", code { o.admin.revokeRole(other.id, owner.id, "OWNER", setOf("*")) })
        assertEquals("ACCOUNT.LAST_ADMIN", code { o.admin.suspend(other.id, owner.id, null, setOf("*")) })
        assertEquals("ACCOUNT.LAST_ADMIN", code { o.deletion.delete(owner.id, dev.sumin.skeleton.account.ReauthInput("tangerine-42-moon"), null) })
        assertEquals(AccountStatus.ACTIVE, o.repo.findById(owner.id)!!.status)
        // ADMIN 은 더 이상 특별하지 않다
        o.repo.grantRole(other.id, "ADMIN", o.time.now())
        o.admin.revokeRole(owner.id, other.id, "ADMIN", setOf("*"))
    }

    @Test
    fun `the bootstrap grants the super role`() {
        val o = AccountHarness(
            props = dev.sumin.skeleton.account.AccountProperties(
                mail = dev.sumin.skeleton.account.AccountProperties.Mail(linkBaseUrl = "https://app.example.com"),
                password = dev.sumin.skeleton.account.AccountProperties.Password(bcryptStrength = 4),
                bootstrap = dev.sumin.skeleton.account.AccountProperties.Bootstrap(adminEmail = "boss@example.com"),
            ),
            superRole = "OWNER",
        )
        val boss = o.activeAccount("boss@example.com")
        assertTrue("OWNER" in o.repo.findById(boss.id)!!.roles)
        assertTrue("ADMIN" !in o.repo.findById(boss.id)!!.roles)
    }

    @Test
    fun `the service itself refuses state commands on a target above the caller - not only the controller`() {
        val boss = h.activeAccount("boss@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val other = h.activeAccount("other@example.com").also { h.repo.grantRole(it.id, "ADMIN", h.time.now()) }
        val cs = setOf("account.read", "account.manage")
        assertEquals("ACCOUNT.ROLE_ESCALATION", code { h.admin.suspend("acc_cs", boss.id, null, cs) })
        h.admin.suspend(other.id, boss.id, null, setOf("*"))   // 전권은 전권을 다룬다
        assertEquals("ACCOUNT.ROLE_ESCALATION", code { h.admin.unsuspend("acc_cs", boss.id, cs) })
        assertEquals("ACCOUNT.ROLE_ESCALATION", code { h.purge.eraseSuspended("acc_cs", boss.id, null, cs) })
        assertEquals("ACCOUNT.ROLE_ESCALATION", code { h.admin.restore("acc_cs", boss.id, cs) })
        assertEquals(AccountStatus.SUSPENDED, h.repo.findById(boss.id)!!.status)
        // 일반 계정은 그대로 다룬다
        val plain = h.activeAccount("plain@example.com")
        h.admin.suspend("acc_cs", plain.id, null, cs)
    }

    @Test
    fun `a grant asks the table to lock the role inside the account transaction, and a role the table says is gone is 400 ROLE_UNKNOWN`() {
        var inTransaction = false
        val atomic = object : dev.sumin.skeleton.account.AccountTransaction {
            override fun <T> run(block: () -> T): T { inTransaction = true; try { return block() } finally { inTransaction = false } }
        }
        val locked = mutableListOf<Pair<String, Boolean>>()
        val gone = setOf("GONE")
        val yml = dev.sumin.skeleton.common.authz.YmlRolePermissionSource(mapOf("ADMIN" to setOf("*"), "GONE" to setOf("account.read"), "KEPT" to setOf("account.read")))
        val source = object : dev.sumin.skeleton.common.authz.RolePermissionSource by yml {
            // 캐시에는 아직 보이지만 표에서는 이미 지워진 역할
            override fun lockForGrant(role: String): Boolean? { locked += role to inTransaction; return role !in gone }
        }
        val x = AccountHarness(atomic = atomic, roleSource = source)
        val target = x.activeAccount("t@example.com")
        assertEquals("ACCOUNT.ROLE_UNKNOWN", code { x.admin.grantRole("acc_x", target.id, "GONE", setOf("*")) })
        assertTrue("GONE" !in x.repo.findById(target.id)!!.roles)
        x.admin.grantRole("acc_x", target.id, "KEPT", setOf("*"))
        assertTrue("KEPT" in x.repo.findById(target.id)!!.roles)
        assertEquals(listOf("GONE" to true, "KEPT" to true), locked, "locked inside the transaction, before the write")
    }
}
