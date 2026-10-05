package dev.sumin.skeleton.account.nooptional

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.account.abuse.AccountAlertAutoConfiguration
import dev.sumin.skeleton.account.abuse.AccountCaptchaAutoConfiguration
import dev.sumin.skeleton.account.mail.AccountMailAutoConfiguration
import dev.sumin.skeleton.account.mail.AccountMailTransport
import dev.sumin.skeleton.account.mail.LogOnlyMailTransport
import dev.sumin.skeleton.account.social.AccountSocialAutoConfiguration
import dev.sumin.skeleton.account.social.AccountSocialWebAutoConfiguration
import dev.sumin.skeleton.account.web.AccountWebAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/**
 * 선택 통합(`notification-mail` · `captcha-turnstile` · `alert` · `auth-social` · `idempotency` · `job-queue-jdbc`)이 클래스패스에 **정말 없을 때**도
 * 계정 모듈이 뜬다 — compileOnly 의존은 소비자에게 따라오지 않으므로, 앱이 그 모듈을 안 더했을 때의 모습이다.
 */
class NoOptionalModulesTest {
    private val runner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(
            AccountMailAutoConfiguration::class.java, AccountCaptchaAutoConfiguration::class.java, AccountAlertAutoConfiguration::class.java,
            AccountSocialAutoConfiguration::class.java, AccountSocialWebAutoConfiguration::class.java, AccountAutoConfiguration::class.java, AccountWebAutoConfiguration::class.java,
        ),
    )

    @Test
    fun `none of the optional modules is on the classpath`() {
        listOf(
            "dev.sumin.skeleton.notification.mail.MailSender", "dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier", "dev.sumin.skeleton.alert.OwnerAlerts",
            "dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry", "dev.sumin.skeleton.idempotency.IdempotentOperation", "dev.sumin.skeleton.jobqueue.jdbc.JobQueue",
        ).forEach { name -> assertTrue(runCatching { Class.forName(name) }.isFailure, "$name must not be on this classpath") }
    }

    @Test
    fun `the module starts, serves its controllers and falls back to log-only mail without them`() {
        runner.withPropertyValues("skeleton.account.password.bcrypt-strength=4").run { ctx ->
            assertTrue(ctx.startupFailure == null, ctx.startupFailure?.toString())
            assertTrue(ctx.getBean(AccountRepository::class.java) is InMemoryAccountRepository)
            assertTrue(ctx.getBean(AccountMailTransport::class.java) is LogOnlyMailTransport)
            assertTrue(ctx.containsBean("accountController"))
            assertTrue(!ctx.containsBean("socialIdentityController"))
        }
    }
}
