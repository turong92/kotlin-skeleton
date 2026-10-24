package dev.sumin.skeleton.account.authz

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import java.time.Instant
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class BoardLikeDeclaration {
    /** board 가 붙은 조립을 흉내 낸다 — 기본 역할 MODERATOR */
    @Bean fun boardLike() = PermissionDeclaration("board", listOf(DeclaredPermission("board.moderate", setOf("MODERATOR")), DeclaredPermission("board.manage", setOf("MODERATOR"))))
}

/** `authz` 모듈 없이 `skeleton.authz.roles` 도 안 준 조립 — 기본 매핑(ADMIN 전권 + 모듈 선언의 기본 역할)이 줄 수 있는 역할이다 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4", "skeleton.account.admin.enabled=true"],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, BoardLikeDeclaration::class)
class AccountAssignableRolesDefaultsTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository

    @Test
    fun `without the authz module the default mapping shows ADMIN and MODERATOR`() {
        val now = Instant.parse("2026-10-06T00:00:00Z")
        check(accounts.insert(Account("acc_defaults_admin", "d@example.com", true, AccountStatus.ACTIVE, setOf("USER", "ADMIN"), null, null, null, now, now), emptyList()))
        mvc.perform(get("/api/v1/admin/accounts/assignable-roles").with(authentication(TestingAuthenticationToken("acc_defaults_admin", "n/a", "ROLE_USER"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(3))
            .andExpect(jsonPath("$.values[0].name").value("ADMIN")).andExpect(jsonPath("$.values[0].permissions[0]").value("*"))
            .andExpect(jsonPath("$.values[1].name").value("MODERATOR")).andExpect(jsonPath("$.values[1].permissions[0]").value("board.manage"))
            .andExpect(jsonPath("$.values[2].name").value("USER")).andExpect(jsonPath("$.values[2].permissions.length()").value(0))
    }
}
