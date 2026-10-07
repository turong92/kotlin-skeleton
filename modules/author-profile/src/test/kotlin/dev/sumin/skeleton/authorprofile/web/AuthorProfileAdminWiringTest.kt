package dev.sumin.skeleton.authorprofile.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.web.AccountCallers
import dev.sumin.skeleton.authorprofile.AuthorProfileAdminDeployGuard
import dev.sumin.skeleton.authorprofile.AuthorProfileAutoConfigurationTest
import dev.sumin.skeleton.authorprofile.T0
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.authentication.TestingAuthenticationToken

/** 관리자 조립 — `account` 의 HTTP 가 꺼져 `AccountCallers` 빈이 없어도 같은 규칙으로 직접 만들어 쓴다 · 배포 가드 빈 */
class AuthorProfileAdminWiringTest {
    private fun runner(vararg props: String) = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AccountAutoConfiguration::class.java, AuthorProfileAdminWebAutoConfiguration::class.java))
        .withUserConfiguration(AuthorProfileAutoConfigurationTest.FakeRepository::class.java)
        .withPropertyValues("skeleton.account.password.bcrypt-strength=4", "skeleton.author-profile.admin.enabled=true", *props)

    private fun Account.withRoles(vararg roles: String) = copy(roles = roles.toSet())

    @Test
    fun `without account's AccountCallers bean the controller builds its own and still enforces the admin role against the stored account`() {
        runner("skeleton.account.http.enabled=false").run { ctx ->
            assertNull(ctx.startupFailure)
            assertTrue(ctx.getBeansOfType(AccountCallers::class.java).isEmpty(), "account's own bean is absent in this setup")
            val controller = ctx.getBean(AuthorProfileAdminController::class.java)
            val accounts = ctx.getBean(AccountRepository::class.java)
            fun stored(id: String, vararg roles: String) = check(accounts.insert(Account(id, "$id@example.com", true, AccountStatus.ACTIVE, roles.toSet(), null, null, null, T0, T0), emptyList()))
            stored("acc_user", "USER")
            stored("acc_boss", "USER", "ADMIN")

            assertEquals("COMMON.UNAUTHORIZED", assertFailsWith<ApplicationException> { controller.byAccount(null, "acc_user") }.errorCode.code)
            assertEquals("COMMON.FORBIDDEN", assertFailsWith<ApplicationException> { controller.byAccount(TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER"), "acc_user") }.errorCode.code)
            // 토큰이 ADMIN 이라 해도 저장소의 계정이 USER 뿐이면 거절
            assertEquals("COMMON.FORBIDDEN", assertFailsWith<ApplicationException> { controller.byAccount(TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER", "ROLE_ADMIN"), "acc_user") }.errorCode.code)
            val ok = controller.byAccount(TestingAuthenticationToken("acc_boss", "n/a", "ROLE_USER", "ROLE_ADMIN"), "acc_user")
            assertEquals("acc_user", ok.value.accountId)
        }
    }

    @Test
    fun `the deploy guard bean exists with admin enabled and warns in prod while only the logging listener is there`() {
        runner().run { ctx ->
            val guard = ctx.getBean(AuthorProfileAdminDeployGuard::class.java)
            assertEquals(1, guard.warnings(DeployContext(DeployEnv.PROD, emptySet())).size)
        }
    }

    @Test
    fun `admin disabled registers neither the controller nor the guard`() {
        runner("skeleton.author-profile.admin.enabled=false").run { ctx ->
            assertTrue(ctx.getBeansOfType(AuthorProfileAdminController::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(AuthorProfileAdminDeployGuard::class.java).isEmpty())
        }
    }
}
