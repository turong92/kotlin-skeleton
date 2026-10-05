package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * 보호 프로필(prod · staging)에서 안전하지 않은 인증 구성으로는 컨텍스트가 뜨지 않는다.
 * 필터 체인 · ObjectMapper 는 이 테스트가 넣어 준다(웹 스택 없이 인증 AutoConfiguration 의 빈 조립만 본다).
 */
class AuthProtectedProfileStartupTest {
    private val strongSecret = "a-production-grade-secret-of-at-least-32-bytes!"

    private fun runner(vararg profiles: String) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthAutoConfiguration::class.java))
        .withBean(ObjectMapper::class.java, { JsonMapper.builder().build() })
        .withBean(SecurityFilterChain::class.java, { DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE) })
        .withInitializer { context -> context.environment.setActiveProfiles(*profiles) }

    private val ownRepository = object : AuthAccountRepository {
        override fun findBy(identifier: AccountIdentifier): AuthAccount? = null
    }

    @Test
    fun `prod with the default jwt secret fails and the message names the property`() {
        runner("prod")
            .withBean(AuthAccountRepository::class.java, { ownRepository })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("skeleton.auth.jwt.secret")
            }
    }

    @Test
    fun `prod with a blank jwt secret fails and the message names the property`() {
        runner("prod")
            .withBean(AuthAccountRepository::class.java, { ownRepository })
            .withPropertyValues("skeleton.auth.jwt.secret=")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("skeleton.auth.jwt.secret")
            }
    }

    @Test
    fun `prod with a strong secret but the seed repository fails and the message names the bean`() {
        runner("prod")
            .withPropertyValues("skeleton.auth.jwt.secret=$strongSecret")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("AuthAccountRepository")
            }
    }

    @Test
    fun `prod with a strong secret and its own repository boots`() {
        runner("prod")
            .withBean(AuthAccountRepository::class.java, { ownRepository })
            .withPropertyValues("skeleton.auth.jwt.secret=$strongSecret")
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `staging is protected by default too`() {
        runner("staging").run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `local dev test and no profile keep booting with the defaults`() {
        listOf(arrayOf("local"), arrayOf("dev"), arrayOf("test"), emptyArray()).forEach { profiles ->
            runner(*profiles).run { context -> assertThat(context).hasNotFailed() }
        }
    }

    @Test
    fun `protected profiles are configurable`() {
        runner("production")
            .withPropertyValues("skeleton.auth.protected-profiles=production")
            .run { context -> assertThat(context).hasFailed() }
        runner("prod")
            .withPropertyValues("skeleton.auth.protected-profiles=production")
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
