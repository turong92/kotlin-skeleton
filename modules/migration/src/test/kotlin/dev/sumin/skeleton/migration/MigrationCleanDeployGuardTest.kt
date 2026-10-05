package dev.sumin.skeleton.migration

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.deploy.DeployGuardViolationException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

/** DB 를 지울 수 있는 설정 가드가 DeployGuard 로 보인다 — 프로필 규칙은 그대로, `skeleton.env` 는 옵트인으로 더 막는다 */
class MigrationCleanDeployGuardTest {
    private fun clean(profile: String? = null, vararg extra: Pair<String, String>): MockEnvironment {
        val env = MockEnvironment().withProperty("skeleton.migration.clean-on-validation-error", "true")
        extra.forEach { env.withProperty(it.first, it.second) }
        if (profile != null) env.setActiveProfiles(profile)
        return env
    }

    @Test
    fun `profile rules are unchanged - prod with clean on is a problem naming the property`() {
        val env = clean("prod")
        val problems = MigrationCleanDeployGuard(env).problems(DeployContext.from(env))

        assertEquals(1, problems.size)
        assertTrue("skeleton.migration.clean-on-validation-error" in problems.single(), problems.toString())
    }

    @Test
    fun `the local profile with the switch unset is fine`() {
        val env = clean("local")

        assertEquals(emptyList(), MigrationCleanDeployGuard(env).problems(DeployContext.from(env)))
    }

    @Test
    fun `the switch in prod refuses clean even when an allowed profile is active`() {
        val env = clean("local", "skeleton.env" to "prod")

        val problems = MigrationCleanDeployGuard(env).problems(DeployContext.from(env))

        assertTrue(problems.single().contains("skeleton.env=prod"), problems.toString())
    }

    @Test
    fun `the switch set to local does not add anything`() {
        val env = clean("local", "skeleton.env" to "local")

        assertEquals(emptyList(), MigrationCleanDeployGuard(env).problems(DeployContext(DeployEnv.LOCAL, setOf("local"))))
    }

    @Test
    fun `the early environment check throws the guard exception with the same text`() {
        val env = clean()

        assertThatThrownBy { MigrationCleanGuardEnvironmentPostProcessor().postProcessEnvironment(env, SpringApplication()) }
            .isInstanceOf(DeployGuardViolationException::class.java)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("skeleton.migration.clean-on-validation-error")
    }

    @Test
    fun `auto-configuration contributes the guard`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MigrationAutoConfiguration::class.java))
            .run { context -> assertThat(context.getBeansOfType(DeployGuard::class.java).values.map { it.name }).containsExactly("migration-clean") }
    }
}
