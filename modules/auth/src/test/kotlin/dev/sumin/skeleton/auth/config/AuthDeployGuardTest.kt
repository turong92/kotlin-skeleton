package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.SeedAuthAccountRepository
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.deploy.DeployGuardAutoConfiguration
import dev.sumin.skeleton.common.deploy.DeployGuardRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/** 인증 가드가 DeployGuard 로 보이고, 프로필 없이 `skeleton.env` 만으로도 같은 규칙이 선다 (스위치를 안 쓰면 이전과 같다) */
class AuthDeployGuardTest {
    private val strongSecret = "a-production-grade-secret-of-at-least-32-bytes!"
    private val ownRepository = object : AuthAccountRepository {
        override fun findBy(identifier: AccountIdentifier): AuthAccount? = null
    }

    @Test
    fun `the switch alone protects - the default secret is a problem without any profile`() {
        val guard = AuthDeployGuard(AuthProperties(), ownRepository)

        val problems = guard.problems(DeployContext(DeployEnv.PROD, emptySet()))

        assertTrue(problems.any { "skeleton.auth.jwt.secret" in it }, problems.toString())
        assertTrue(problems.none { strongSecret in it })
    }

    @Test
    fun `with neither the switch nor a protected profile there are no problems`() {
        val guard = AuthDeployGuard(AuthProperties(), SeedAuthAccountRepository(emptyList()))

        assertEquals(emptyList(), guard.problems(DeployContext(null, setOf("local"))))
        assertEquals(emptyList(), guard.problems(DeployContext(DeployEnv.LOCAL, emptySet())))
    }

    @Test
    fun `the profile list keeps working with the switch unset and reports every problem`() {
        val guard = AuthDeployGuard(AuthProperties(), SeedAuthAccountRepository(emptyList()))

        val problems = guard.problems(DeployContext(null, setOf("prod")))

        assertTrue(problems.any { "skeleton.auth.jwt.secret" in it })
        assertTrue(problems.any { "AuthAccountRepository" in it })
    }

    @Test
    fun `a strong secret and an own repository pass in prod`() {
        val guard = AuthDeployGuard(AuthProperties(jwt = AuthProperties.Jwt(secret = strongSecret)), ownRepository)

        assertEquals(emptyList(), guard.problems(DeployContext(DeployEnv.PROD, setOf("prod"))))
    }

    @Test
    fun `auto-configuration contributes the guard and the platform runner lists it`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthAutoConfiguration::class.java, DeployGuardAutoConfiguration::class.java))
            .withBean(ObjectMapper::class.java, { JsonMapper.builder().build() })
            .withBean(SecurityFilterChain::class.java, { DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE) })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(DeployGuard::class.java).values.map { it.name }).contains("auth")
                assertThat(context.getBean(DeployGuardRunner::class.java).report!!.guards).contains("auth")
            }
    }

    @Test
    fun `the switch in prod fails the context even without the prod profile`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthAutoConfiguration::class.java, DeployGuardAutoConfiguration::class.java))
            .withBean(ObjectMapper::class.java, { JsonMapper.builder().build() })
            .withBean(SecurityFilterChain::class.java, { DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE) })
            .withBean(AuthAccountRepository::class.java, { ownRepository })
            .withPropertyValues("skeleton.env=prod")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("skeleton.auth.jwt.secret")
            }
    }
}
