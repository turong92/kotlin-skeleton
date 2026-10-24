package dev.sumin.skeleton.account.authz

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 계정 관리 API 는 엔드포인트마다 권한 하나, 판정은 매 요청 저장소 — 같은 토큰으로 회수 · 부여가 바로 먹는다 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.admin.enabled=true",
        "skeleton.authz.roles.ADMIN=*",
        "skeleton.authz.roles.VIEWER=account.read",
        "skeleton.authz.roles.ROLER=account.roles",
        "skeleton.authz.roles.STAFF=account.read,account.manage",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class AccountAuthzWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository
    private val seq = AtomicInteger()
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    private fun store(vararg roles: String, status: AccountStatus = AccountStatus.ACTIVE): String {
        val id = "acc_az_${seq.incrementAndGet()}_${System.nanoTime()}"
        check(accounts.insert(Account(id, "$id@example.com", true, status, roles.toSet(), null, null, null, now, now), emptyList()))
        return id
    }

    /** 토큰의 역할은 늘 ADMIN — 판정이 토큰을 보지 않음을 보이려고 */
    private fun token(id: String) = authentication(TestingAuthenticationToken(id, "n/a", "ROLE_ADMIN"))

    @Test
    fun `a revoked operator gets 403 on the next call with the same token - a suspended one too`() {
        val admin = store("USER", "ADMIN")
        mvc.perform(get("/api/v1/admin/accounts").with(token(admin))).andExpect(status().isOk)
        accounts.revokeRole(admin, "ADMIN", now)
        mvc.perform(get("/api/v1/admin/accounts").with(token(admin))).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PLATFORM.FORBIDDEN"))

        val suspended = store("USER", "ADMIN", status = AccountStatus.SUSPENDED)
        mvc.perform(get("/api/v1/admin/accounts").with(token(suspended))).andExpect(status().isForbidden)
    }

    @Test
    fun `a role granted after the token was issued opens the API at once`() {
        val user = store("USER")
        mvc.perform(get("/api/v1/admin/accounts").with(token(user))).andExpect(status().isForbidden)
        accounts.grantRole(user, "VIEWER", now)
        mvc.perform(get("/api/v1/admin/accounts").with(token(user))).andExpect(status().isOk)
    }

    @Test
    fun `account read alone searches but cannot suspend, erase, or touch roles`() {
        val viewer = store("VIEWER")
        val target = store("USER")
        mvc.perform(get("/api/v1/admin/accounts/$target").with(token(viewer))).andExpect(status().isOk)
        mvc.perform(get("/api/v1/admin/accounts/blocks").with(token(viewer))).andExpect(status().isOk)
        mvc.perform(post("/api/v1/admin/accounts/$target/suspend").with(token(viewer))).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/admin/accounts/$target/erase").with(token(viewer))).andExpect(status().isForbidden)
        mvc.perform(delete("/api/v1/admin/accounts/blocks/1").with(token(viewer))).andExpect(status().isForbidden)
        mvc.perform(put("/api/v1/admin/accounts/$target/roles/VIEWER").with(token(viewer))).andExpect(status().isForbidden)
        mvc.perform(delete("/api/v1/admin/accounts/$target/roles/VIEWER").with(token(viewer))).andExpect(status().isForbidden)
    }

    @Test
    fun `account manage suspends and account roles alone does not search`() {
        val staff = store("STAFF")
        val roler = store("ROLER")
        val target = store("USER")
        mvc.perform(post("/api/v1/admin/accounts/$target/suspend").with(token(staff))).andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/admin/accounts").with(token(roler))).andExpect(status().isForbidden)
    }

    @Test
    fun `granting is bounded by the callers own permissions and by the defined roles`() {
        val roler = store("ROLER")
        val target = store("USER")
        mvc.perform(put("/api/v1/admin/accounts/$target/roles/ADMIN").with(token(roler))).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.ROLE_ESCALATION"))
        mvc.perform(put("/api/v1/admin/accounts/$target/roles/VIEWER").with(token(roler))).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.ROLE_ESCALATION"))
        mvc.perform(put("/api/v1/admin/accounts/$target/roles/GHOST").with(token(roler))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.ROLE_UNKNOWN"))
        mvc.perform(put("/api/v1/admin/accounts/$target/roles/ROLER").with(token(roler))).andExpect(status().isNoContent)
        mvc.perform(put("/api/v1/admin/accounts/$target/roles/USER").with(token(roler))).andExpect(status().isNoContent)
        assertTrue("ROLER" in accounts.findById(target)!!.roles)

        val admin = store("ADMIN")
        mvc.perform(put("/api/v1/admin/accounts/$target/roles/ADMIN").with(token(admin))).andExpect(status().isNoContent)
    }

    @Test
    fun `a signed-in caller with no permission is 403 and anonymous is 401`() {
        mvc.perform(get("/api/v1/admin/accounts").with(token(store("USER")))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/accounts")).andExpect(status().isUnauthorized)
    }
}
