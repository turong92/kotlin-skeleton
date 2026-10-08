package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 이메일 필수의 403 은 계정 HTTP(컨트롤러)를 껐을 때도 켜져 있다 — `me.emailRequired` 와 첫 등록 면제는 컨트롤러와 무관하게 `required` 만 보기 때문이다 */
class EmailRequiredWiringTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AccountAutoConfiguration::class.java, AccountWebAutoConfiguration::class.java, AccountEmailRequiredAutoConfiguration::class.java))
        .withPropertyValues("skeleton.account.password.bcrypt-strength=4")

    private fun configurers(ctx: org.springframework.context.ApplicationContext) = ctx.getBeansOfType(WebMvcConfigurer::class.java).keys.filter { it == "accountEmailRequiredConfigurer" }

    @Test
    fun `required on registers the guard also when the account HTTP is off`() {
        runner.withPropertyValues("skeleton.account.email.required=true", "skeleton.account.http.enabled=false").run { ctx ->
            assertEquals(listOf("accountEmailRequiredConfigurer"), configurers(ctx))
            assertTrue(ctx.getBeansOfType(AccountController::class.java).isEmpty(), "the controllers really are off")
        }
    }

    @Test
    fun `required on with the account HTTP on registers it once, and required off registers nothing`() {
        runner.withPropertyValues("skeleton.account.email.required=true").run { ctx -> assertEquals(1, configurers(ctx).size) }
        runner.run { ctx -> assertTrue(configurers(ctx).isEmpty()) }
    }

    @Test
    fun `an extra exclude rule that cannot be read stops the context from starting and names the setting`() {
        runner.withPropertyValues("skeleton.account.email.required=true", "skeleton.account.email.extra-exclude-paths=GET api/v1/x").run { ctx ->
            val message = generateSequence(ctx.startupFailure as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("skeleton.account.email.extra-exclude-paths" in message, message)
        }
    }
}
