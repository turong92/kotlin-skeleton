package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 아이디 게이트는 `login-id.enabled` 만 본다 — 계정 HTTP 를 꺼도 서 있고, 꺼짐(기본)이면 인터셉터도 컨트롤러도 없다. 순서는 아이디(-110) → 이메일(-100) → 약관(0) */
class LoginIdRequiredWiringTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AccountAutoConfiguration::class.java, AccountWebAutoConfiguration::class.java, AccountEmailRequiredAutoConfiguration::class.java, AccountLoginIdRequiredAutoConfiguration::class.java))
        .withPropertyValues("skeleton.account.password.bcrypt-strength=4")

    private fun configurers(ctx: org.springframework.context.ApplicationContext) = ctx.getBeansOfType(WebMvcConfigurer::class.java).keys.filter { it == "accountLoginIdRequiredConfigurer" }

    @Test
    fun `on registers the gate also when the account HTTP is off`() {
        runner.withPropertyValues("skeleton.account.login-id.enabled=true", "skeleton.account.http.enabled=false").run { ctx ->
            assertEquals(listOf("accountLoginIdRequiredConfigurer"), configurers(ctx))
            assertTrue(ctx.getBeansOfType(LoginIdController::class.java).isEmpty(), "the controllers really are off")
        }
    }

    @Test
    fun `on with the account HTTP on registers the gate and the controller once`() {
        runner.withPropertyValues("skeleton.account.login-id.enabled=true").run { ctx ->
            assertEquals(1, configurers(ctx).size)
            assertEquals(1, ctx.getBeansOfType(LoginIdController::class.java).size)
            assertEquals(1, ctx.getBeansOfType(LoginIdAvailabilityController::class.java).size)
        }
    }

    @Test
    fun `off - the default - registers neither the gate nor the controller`() {
        runner.run { ctx ->
            assertTrue(configurers(ctx).isEmpty())
            assertTrue(ctx.getBeansOfType(LoginIdController::class.java).isEmpty())
        }
        runner.withPropertyValues("skeleton.account.email.required=true").run { ctx -> assertTrue(configurers(ctx).isEmpty(), "the email gate alone does not bring the id gate") }
    }

    @Test
    fun `the id gate runs before the email gate, which runs before the default order of the re-consent gate`() {
        assertTrue(AccountLoginIdRequiredAutoConfiguration.LOGIN_ID_REQUIRED_ORDER < AccountEmailRequiredAutoConfiguration.EMAIL_REQUIRED_ORDER)
        assertTrue(AccountEmailRequiredAutoConfiguration.EMAIL_REQUIRED_ORDER < 0)
        assertEquals(-110, AccountLoginIdRequiredAutoConfiguration.LOGIN_ID_REQUIRED_ORDER)
    }

    @Test
    fun `an extra exclude rule that cannot be read stops the context and names the id setting`() {
        runner.withPropertyValues("skeleton.account.login-id.enabled=true", "skeleton.account.login-id.extra-exclude-paths=GET api/v1/x").run { ctx ->
            val message = generateSequence(ctx.startupFailure as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("skeleton.account.login-id.extra-exclude-paths" in message, message)
        }
    }
}
