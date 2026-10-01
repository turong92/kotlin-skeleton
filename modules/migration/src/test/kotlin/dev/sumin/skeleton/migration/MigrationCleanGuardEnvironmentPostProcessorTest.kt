package dev.sumin.skeleton.migration

import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class MigrationCleanGuardEnvironmentPostProcessorTest {
    private val processor = MigrationCleanGuardEnvironmentPostProcessor()
    private fun run(env: MockEnvironment) = processor.postProcessEnvironment(env, SpringApplication())

    @Test
    fun `leaves flyway settings to the app`() {
        // 스켈레톤 모듈은 Flyway 기본값을 바꾸지 않는다 — out-of-order 등은 앱이 자기 yml 에 적는 선택
        val env = MockEnvironment()
        run(env)
        assertEquals(null, env.getProperty("spring.flyway.out-of-order"))
        assertEquals(null, env.getProperty("spring.flyway.validate-migration-naming"))
        assertEquals(null, env.getProperty("spring.flyway.locations"))
    }

    @Test
    fun `clean on validation error is allowed in the local profile`() {
        val env = MockEnvironment().withProperty("skeleton.migration.clean-on-validation-error", "true")
        env.setActiveProfiles("local")
        run(env)
    }

    @Test
    fun `clean on validation error without any active profile fails`() {
        val env = MockEnvironment().withProperty("skeleton.migration.clean-on-validation-error", "true")
        assertThatThrownBy { run(env) }.hasMessageContaining("skeleton.migration.clean-on-validation-error").hasMessageContaining("local")
    }

    @Test
    fun `flyway clean enabled in prod fails`() {
        val env = MockEnvironment().withProperty("spring.flyway.clean-disabled", "false")
        env.setActiveProfiles("prod")
        assertThatThrownBy { run(env) }.hasMessageContaining("spring.flyway.clean-disabled").hasMessageContaining("prod")
    }

    @Test
    fun `liquibase drop-first outside allowed profiles fails`() {
        val env = MockEnvironment().withProperty("spring.liquibase.drop-first", "true")
        env.setActiveProfiles("prod")
        assertThatThrownBy { run(env) }.hasMessageContaining("spring.liquibase.drop-first").hasMessageContaining("prod")
    }

    @Test
    fun `allowed profiles are configurable`() {
        val env = MockEnvironment()
            .withProperty("skeleton.migration.clean-on-validation-error", "true")
            .withProperty("skeleton.migration.clean-allowed-profiles", "local,dev")
        env.setActiveProfiles("dev")
        run(env)
    }
}
