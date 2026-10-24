package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.authorprofile.T0
import dev.sumin.skeleton.authorprofiletest.AuthorProfileWebTestApplication
import dev.sumin.skeleton.authorprofiletest.FakeAuthorProfileStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 연결 조회는 권한 `author-profile.read` 하나 — 역할 이름이 아니라 권한이 연다, 판정은 매 호출 저장소 */
@SpringBootTest(
    classes = [AuthorProfileWebTestApplication::class],
    properties = [
        "skeleton.author-profile.admin.enabled=true",
        "skeleton.authz.roles.ADMIN=*",
        "skeleton.authz.roles.READER=author-profile.read",
        "skeleton.authz.roles.STAFF=account.read",
    ],
)
@AutoConfigureMockMvc
@Import(FakeAuthorProfileStore::class)
class AuthorProfileAdminAuthzTest : WebTestSupport() {
    private val seq = AtomicInteger()

    /** 토큰의 역할은 늘 ADMIN — 판정이 토큰을 보지 않음을 보이려고 */
    private fun operator(vararg roles: String, status: AccountStatus = AccountStatus.ACTIVE): TestingAuthenticationToken {
        val id = "acc_ap_${seq.incrementAndGet()}_${System.nanoTime()}"
        check(accounts.insert(Account(id, "$id@example.com", true, status, roles.toSet(), null, null, null, T0, T0), emptyList()))
        return TestingAuthenticationToken(id, "n/a", "ROLE_ADMIN")
    }

    private val url = "/api/v1/admin/author-profiles"

    @Test
    fun `a role holding the read permission opens the lookup - a role with other permissions does not`() {
        call(get(url).param("accountId", "acc_none"), operator("USER", "READER")).andExpect(status().isNotFound)
        call(get(url).param("accountId", "acc_none"), operator("USER", "STAFF")).andExpect(status().isForbidden)
    }

    @Test
    fun `revoking the role closes the lookup on the next call with the same token`() {
        val who = operator("USER", "READER")
        call(get(url).param("accountId", "acc_none"), who).andExpect(status().isNotFound)
        accounts.revokeRole(who.name, "READER", T0)
        call(get(url).param("accountId", "acc_none"), who).andExpect(status().isForbidden)
    }

    @Test
    fun `a suspended holder is refused`() {
        call(get(url).param("accountId", "acc_none"), operator("USER", "READER", status = AccountStatus.SUSPENDED)).andExpect(status().isForbidden)
    }
}
