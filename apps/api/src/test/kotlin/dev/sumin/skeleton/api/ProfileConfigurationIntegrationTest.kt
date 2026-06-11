package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.config.ConfigValidationAutoConfiguration
import dev.sumin.skeleton.common.config.ConfigValidationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ProfileConfigurationIntegrationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigValidationAutoConfiguration::class.java))

    @Test
    fun `local passes when ssm is disabled and dev prod requirements are missing`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=local",
                *requirement(0, "skeleton.auth.jwt.secret", "JWT_SECRET", "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret", "dev,staging,prod"),
                "skeleton.config.validation.requirements[0].secret=true",
                "skeleton.config.aws.ssm.enabled=false",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                runValidation(context.getBean(ApplicationRunner::class.java))
            }
    }

    @Test
    fun `dev passes when required values are supplied`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                *requirement(0, "skeleton.auth.jwt.secret", "JWT_SECRET", "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret", "dev,staging,prod"),
                "skeleton.config.validation.requirements[0].secret=true",
                *requirement(1, "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD", "/kotlin-skeleton/{profile}/spring.datasource.password", "dev,staging,prod"),
                "skeleton.config.validation.requirements[1].secret=true",
                "skeleton.auth.jwt.secret=dev-test-secret-32-bytes-change",
                "spring.datasource.password=dev-db-password",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                runValidation(context.getBean(ApplicationRunner::class.java))
            }
    }

    @Test
    fun `dev failure lists missing property env and ssm path`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                *requirement(0, "skeleton.auth.jwt.secret", "JWT_SECRET", "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret", "dev,staging,prod"),
                "skeleton.config.validation.requirements[0].secret=true",
            )
            .run { context ->
                val error = assertFailsWith<ConfigValidationException> {
                    runValidation(context.getBean(ApplicationRunner::class.java))
                }
                val message = error.message.orEmpty()
                assertTrue(message.contains("skeleton.auth.jwt.secret"))
                assertTrue(message.contains("JWT_SECRET"))
                assertTrue(message.contains("/kotlin-skeleton/dev/skeleton.auth.jwt.secret"))
            }
    }

    @Test
    fun `prod rejects dummy secret even when value exists`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                *requirement(0, "skeleton.auth.jwt.secret", "JWT_SECRET", "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret", "prod"),
                "skeleton.config.validation.requirements[0].secret=true",
                "skeleton.auth.jwt.secret=dev-local-jwt-secret-change-me-32-bytes",
            )
            .run { context ->
                val error = assertFailsWith<ConfigValidationException> {
                    runValidation(context.getBean(ApplicationRunner::class.java))
                }
                val message = error.message.orEmpty()
                assertTrue(message.contains("dummy"))
                assertTrue(message.contains("/kotlin-skeleton/prod/skeleton.auth.jwt.secret"))
            }
    }

    private fun requirement(
        index: Int,
        property: String,
        env: String,
        ssm: String,
        profiles: String,
    ): Array<String> =
        (
            listOf(
            "skeleton.config.validation.requirements[$index].property=$property",
            "skeleton.config.validation.requirements[$index].env=$env",
            "skeleton.config.validation.requirements[$index].ssm=$ssm",
        ) + profiles.split(",").mapIndexed { profileIndex, profile ->
                "skeleton.config.validation.requirements[$index].profiles[$profileIndex]=$profile"
            }
            ).toTypedArray()

    private fun runValidation(runner: ApplicationRunner) {
        runner.run(DefaultApplicationArguments(*emptyArray<String>()))
    }
}
