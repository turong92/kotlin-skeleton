package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드에서도 보호 환경 가드가 돈다 - 빠지면 결제사가 조용히 사라지거나 서명한 자격 · 푸시 검증 키가 딴 주소로 간다. */
class GooglePlayProjectGuardTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, GooglePlayPaymentAutoConfiguration::class.java))
        .withPropertyValues("skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb")
    private val accounts = mapOf("a" to TestServiceAccount(), "b" to TestServiceAccount())
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())

    private fun store(project: String, vararg skip: String) = listOf(
        "enabled=true", "package-name=$PACKAGE", "service-account=${accounts.getValue(project).base64}", "pubsub.audience=$AUDIENCE", "pubsub.service-account-email=$PUSH_EMAIL",
    ).filterNot { line -> skip.any { line.startsWith(it) } }.map { "skeleton.projects.$project.config.payment-googleplay.$it" }.toTypedArray()

    @Test
    fun `a protected environment does not start when a project switched the store on with a value missing - naming the project`() {
        runner.withPropertyValues(*store("a"), *store("b", "package-name")).run { ctx ->
            val guard = ctx.getBean("googlePlayProjectGuard", DeployGuard::class.java)
            assertThat(guard.problems(prod)).singleElement().asString().startsWith("project 'b':").contains("package-name")
            assertThat(guard.problems(local)).isEmpty()
        }
    }

    @Test
    fun `a protected environment does not start when a project points the certificate or token address elsewhere`() {
        for (key in listOf("certs-url", "token-url")) {
            runner.withPropertyValues(*store("a"), *store("b"), "skeleton.projects.b.config.payment-googleplay.$key=https://proxy.example.com/SECRET-PATH").run { ctx ->
                val guard = ctx.getBean("googlePlayProjectGuard", DeployGuard::class.java)
                assertThat(guard.problems(prod)).`as`(key).singleElement().asString().startsWith("project 'b':").contains(key).doesNotContain("SECRET-PATH")
                assertThat(guard.problems(local)).isEmpty()
            }
        }
    }

    @Test
    fun `a project that did not switch the store on is not judged, and a complete one passes`() {
        runner.withPropertyValues(*store("a"), "skeleton.projects.b.config.payment-googleplay.certs-url=https://proxy.example.com/x").run { ctx ->
            assertThat(ctx.getBean("googlePlayProjectGuard", DeployGuard::class.java).problems(prod)).isEmpty()
        }
    }
}
