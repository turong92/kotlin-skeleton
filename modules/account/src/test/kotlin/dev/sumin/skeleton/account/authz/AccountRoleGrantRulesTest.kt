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
        assertEquals("ACCOUNT.LAST_ADMIN", code { o.admin.revokeRole(other.id, owner.id, "OWNER") })
        assertEquals("ACCOUNT.LAST_ADMIN", code { o.admin.suspend(other.id, owner.id, null) })
        assertEquals("ACCOUNT.LAST_ADMIN", code { o.deletion.delete(owner.id, dev.sumin.skeleton.account.ReauthInput("tangerine-42-moon"), null) })
        assertEquals(AccountStatus.ACTIVE, o.repo.findById(owner.id)!!.status)
        // ADMIN 은 더 이상 특별하지 않다
        o.repo.grantRole(other.id, "ADMIN", o.time.now())
        o.admin.revokeRole(owner.id, other.id, "ADMIN")
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
}
