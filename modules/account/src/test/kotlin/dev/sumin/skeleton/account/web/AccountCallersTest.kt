package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/** I2 — 관리자 권한은 토큰이 들고 온 역할이 아니라 **지금 저장소의 계정**이 정한다 (정지 · 회수된 관리자가 남은 액세스 토큰으로 조치를 되돌리지 못하게) */
class AccountCallersTest {
    private val repo = InMemoryAccountRepository()
    private val callers = AccountCallers(AccountProperties.Admin(enabled = true)) { repo }
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    private fun store(id: String, status: AccountStatus, roles: Set<String>) =
        repo.insert(Account(id, "$id@example.com", true, status, roles, null, null, null, now, now), emptyList())

    /** 토큰에는 ADMIN 이 들어 있다 (발급 뒤에 저장소에서 바뀌었을 수 있다) */
    private fun tokenFor(id: String) = UsernamePasswordAuthenticationToken(id, "n/a", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))

    private fun forbidden(block: () -> Unit) = assertEquals("COMMON.FORBIDDEN", assertFailsWith<ApplicationException> { block() }.errorCode.code)

    @Test
    fun `an administrator who is still active and still has the role passes`() {
        store("acc_ok", AccountStatus.ACTIVE, setOf("USER", "ADMIN"))
        assertEquals("acc_ok", callers.requireAdmin(tokenFor("acc_ok")).accountId)
    }

    @Test
    fun `a suspended administrator is refused with the token still in hand`() {
        store("acc_susp", AccountStatus.SUSPENDED, setOf("USER", "ADMIN"))
        forbidden { callers.requireAdmin(tokenFor("acc_susp")) }
    }

    @Test
    fun `an administrator whose role was revoked is refused with the token still in hand`() {
        store("acc_demoted", AccountStatus.ACTIVE, setOf("USER"))
        forbidden { callers.requireAdmin(tokenFor("acc_demoted")) }
    }

    @Test
    fun `a deleted or missing account is refused`() {
        store("acc_gone", AccountStatus.DELETED, setOf("USER", "ADMIN"))
        forbidden { callers.requireAdmin(tokenFor("acc_gone")) }
        forbidden { callers.requireAdmin(tokenFor("acc_never")) }
    }
}
