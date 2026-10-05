package dev.sumin.skeleton.common.deploy

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DeployGuardRunnerTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DeployGuardAutoConfiguration::class.java))

    /** 보호 환경에서만 문제를 내는 보통의 모듈 가드 — 비밀 이름만 말하고 값은 싣지 않는다 */
    private class NeedsSecretGuard : DeployGuard {
        override val name = "demo-secret"
        override fun problems(context: DeployContext) =
            if (context.protectedEnv) listOf("DEMO_API_KEY is not set") else emptyList()
        override fun warnings(context: DeployContext) =
            if (context.env == DeployEnv.STAGE) listOf("DEMO_WEBHOOK_URL is not set — alerts are logged only") else emptyList()
    }

    @Test
    fun `without the switch nothing changes - protected-env guards stay quiet and the app starts`() {
        runner.withBean(DeployGuard::class.java, { NeedsSecretGuard() }).run { context ->
            assertThat(context).hasNotFailed()
            val report = context.getBean(DeployGuardRunner::class.java).report!!
            assertThat(report.problems).isEmpty()
            assertThat(report.context.env).isNull()
        }
    }

    @Test
    fun `in prod a guard problem fails startup with the guard name and the variable name`() {
        runner.withBean(DeployGuard::class.java, { NeedsSecretGuard() })
            .withPropertyValues("skeleton.env=prod")
            .run { context ->
                assertThat(context).hasFailed()
                val failure = context.startupFailure
                assertThat(failure).hasStackTraceContaining("DEMO_API_KEY is not set")
                val violation = generateSequence<Throwable>(failure) { it.cause }
                    .filterIsInstance<DeployGuardViolationException>().first()
                assertThat(violation.problems.map { it.guard }).containsExactly("demo-secret")
            }
    }

    @Test
    fun `in local the same guard passes`() {
        runner.withBean(DeployGuard::class.java, { NeedsSecretGuard() })
            .withPropertyValues("skeleton.env=local")
            .run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `warnings do not fail startup and are kept in the report`() {
        runner.withBean(DeployGuard::class.java, { NeedsSecretGuard() })
            .withPropertyValues("skeleton.env=stage")
            .run { context ->
                // stage 는 보호 환경이라 problems 가 먼저 막는다 — 경고만 보려면 problems 가 없어야 한다
                assertThat(context).hasFailed()
            }
        runner.withBean(DeployGuard::class.java, {
            object : DeployGuard {
                override val name = "warn-only"
                override fun warnings(context: DeployContext) = listOf("DEMO_WEBHOOK_URL is not set")
            }
        }).withPropertyValues("skeleton.env=stage").run { context ->
            assertThat(context).hasNotFailed()
            val report = context.getBean(DeployGuardRunner::class.java).report!!
            assertThat(report.warnings.map { it.message }).containsExactly("DEMO_WEBHOOK_URL is not set")
        }
    }

    @Test
    fun `the summary lists every guard with its state and the environment`() {
        runner.withBean(DeployGuard::class.java, { NeedsSecretGuard() })
            .withPropertyValues("skeleton.env=local")
            .run { context ->
                val summary = context.getBean(DeployGuardRunner::class.java).report!!.summary()
                assertThat(summary).contains("env=local").contains("demo-secret").contains("deploy-env")
            }
    }

    @Test
    fun `unset switch is shown as unset in the summary`() {
        runner.run { context ->
            val summary = context.getBean(DeployGuardRunner::class.java).report!!.summary()
            assertThat(summary).contains("env=unset").contains("SKELETON_ENV")
        }
    }

    @Test
    fun `require-env makes an unset switch fail startup`() {
        runner.withPropertyValues("skeleton.deploy.require-env=true").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("SKELETON_ENV")
        }
    }

    @Test
    fun `an unknown switch value fails startup even when guards are quiet`() {
        runner.withPropertyValues("skeleton.env=production").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("local | stage | prod")
        }
    }

    @Test
    fun `the switch and the spring profile disagreeing is a warning, not a failure`() {
        runner.withPropertyValues("skeleton.env=prod").run { context ->
            assertThat(context).hasNotFailed()
            val warnings = context.getBean(DeployGuardRunner::class.java).report!!.warnings
            assertThat(warnings.map { it.guard }).contains("deploy-env")
        }
    }

    @Test
    fun `an app can replace the runner and the env guard`() {
        runner.withBean("deployGuardRunner", DeployGuardRunner::class.java, { DeployGuardRunner(emptyList(), DeployContext(null, emptySet())) })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DeployGuardRunner::class.java)
            }
    }
}
