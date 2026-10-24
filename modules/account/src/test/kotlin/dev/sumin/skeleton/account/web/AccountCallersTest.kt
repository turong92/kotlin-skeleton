package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.account.authz.AccountOperatorAccess
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.authz.YmlRolePermissionSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/** I2 — 운영 권한은 토큰이 들고 온 역할이 아니라 **지금 저장소의 계정**이 정한다 (정지 · 회수된 운영자가 남은 액세스 토큰으로 조치를 되돌리지 못하게) */
class AccountCallersTest {
    private val repo = InMemoryAccountRepository()
    private val source = YmlRolePermissionSource(mapOf("ADMIN" to setOf("*"), "VIEWER" to setOf("account.read")))
    private val callers = AccountCallers { AccountOperatorAccess({ repo }, { source }) }
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    private fun store(id: String, status: AccountStatus, roles: Set<String>) =
        repo.insert(Account(id, "$id@example.com", true, status, roles, null, null, null, now, now), emptyList())

    /** 토큰에는 ADMIN 이 들어 있다 (발급 뒤에 저장소에서 바뀌었을 수 있다) */
    private fun tokenFor(id: String) = UsernamePasswordAuthenticationToken(id, "n/a", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))

    private fun forbidden(block: () -> Unit) = assertEquals("COMMON.FORBIDDEN", assertFailsWith<ApplicationException> { block() }.errorCode.code)

    @Test
    fun `an operator who is still active and still has the role passes`() {
        store("acc_ok", AccountStatus.ACTIVE, setOf("USER", "ADMIN"))
        assertEquals("acc_ok", callers.requirePermission(tokenFor("acc_ok"), "account.manage").accountId)
    }

    @Test
    fun `a suspended operator is refused with the token still in hand`() {
        store("acc_susp", AccountStatus.SUSPENDED, setOf("USER", "ADMIN"))
        forbidden { callers.requirePermission(tokenFor("acc_susp"), "account.read") }
    }

    @Test
    fun `an operator whose role was revoked is refused with the token still in hand`() {
        store("acc_demoted", AccountStatus.ACTIVE, setOf("USER"))
        forbidden { callers.requirePermission(tokenFor("acc_demoted"), "account.read") }
    }

    @Test
    fun `a deleted or missing account is refused`() {
        store("acc_gone", AccountStatus.DELETED, setOf("USER", "ADMIN"))
        forbidden { callers.requirePermission(tokenFor("acc_gone"), "account.read") }
        forbidden { callers.requirePermission(tokenFor("acc_never"), "account.read") }
    }

    @Test
    fun `a permission the stored role lacks is refused, one it has passes, anonymous is 401`() {
        store("acc_view", AccountStatus.ACTIVE, setOf("VIEWER"))
        callers.requirePermission(tokenFor("acc_view"), "account.read")
        forbidden { callers.requirePermission(tokenFor("acc_view"), "account.manage") }
        assertEquals("COMMON.UNAUTHORIZED", assertFailsWith<ApplicationException> { callers.requirePermission(null, "account.read") }.errorCode.code)
    }
}
