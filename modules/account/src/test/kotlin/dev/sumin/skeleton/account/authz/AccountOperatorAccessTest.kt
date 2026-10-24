package dev.sumin.skeleton.account.authz

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.YmlRolePermissionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 판정은 토큰의 역할이 아니라 매 요청 저장소의 계정이 정한다 — 회수 · 정지 · 부여가 유효한 토큰을 쥔 채로 다음 호출부터 먹는다 */
class AccountOperatorAccessTest {
    private val h = AccountHarness()
    private val source = YmlRolePermissionSource(
        mapOf("ADMIN" to setOf("*"), "VIEWER" to setOf("account.read"), "STAFF" to setOf("account.read", "account.manage")),
    )
    private val access = AccountOperatorAccess({ h.repo }, { source })
    private val now get() = h.time.now()

    private fun store(id: String, status: AccountStatus, vararg roles: String) =
        h.repo.insert(Account(id, "$id@example.com", true, status, roles.toSet(), null, null, null, now, now), emptyList())

    /** 토큰에는 늘 ADMIN 이 들어 있다 — 저장소와 다를 수 있다 */
    private fun staleToken(id: String) = OperatorRef(id, setOf("ADMIN"))

    @Test
    fun `revoking the role takes effect on the next call although the token still says ADMIN`() {
        store("acc_1", AccountStatus.ACTIVE, "USER", "ADMIN")
        assertEquals(setOf("*"), access.permissionsOf(staleToken("acc_1")))
        h.repo.revokeRole("acc_1", "ADMIN", now)
        assertEquals(emptySet(), access.permissionsOf(staleToken("acc_1")))
        assertTrue(!access.has(staleToken("acc_1"), "account.read"))
    }

    @Test
    fun `a suspended or deleted or missing operator has no permissions`() {
        store("acc_susp", AccountStatus.SUSPENDED, "ADMIN")
        store("acc_del", AccountStatus.DELETED, "ADMIN")
        listOf("acc_susp", "acc_del", "acc_never").forEach { assertEquals(emptySet(), access.permissionsOf(staleToken(it)), it) }
    }

    @Test
    fun `granting a role after the token was issued takes effect without a new token - the token roles are not read`() {
        store("acc_2", AccountStatus.ACTIVE, "USER")
        val tokenWithoutRoles = OperatorRef("acc_2", emptySet())
        assertTrue(!access.has(tokenWithoutRoles, "account.read"))
        h.repo.grantRole("acc_2", "VIEWER", now)
        assertTrue(access.has(tokenWithoutRoles, "account.read"))
        assertTrue(!access.has(tokenWithoutRoles, "account.manage"))
    }

    @Test
    fun `permissions of several roles are merged and sorted`() {
        store("acc_3", AccountStatus.ACTIVE, "VIEWER", "STAFF")
        assertEquals(listOf("account.manage", "account.read"), access.permissionsOf(OperatorRef("acc_3")).toList())
    }

    @Test
    fun `inside one request the repository is read once`() {
        store("acc_4", AccountStatus.ACTIVE, "VIEWER")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        try {
            h.callLog.calls.clear()
            access.permissionsOf(OperatorRef("acc_4")); access.permissionsOf(OperatorRef("acc_4")); access.has(OperatorRef("acc_4"), "account.read")
            assertEquals(1, h.callLog.calls.count { it.second == "findById" })
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }
}
