package dev.sumin.skeleton.payment.appstore

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

/** 프로젝트 모드에서도 보호 환경 가드가 돈다(토스의 프로젝트 가드와 같은 규칙) - 빠지면 결제사가 조용히 사라지거나 서명 토큰이 딴 주소로 간다. */
class AppStoreProjectGuardTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, AppStorePaymentAutoConfiguration::class.java))
        .withPropertyValues("skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb")
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())

    private fun store(project: String, vararg skip: String) = listOf(
        "enabled=true", "bundle-id=$BUNDLE", "app-apple-id=$APP_APPLE_ID", "issuer-id=$ISSUER", "key-id=$KEY_ID", "private-key=PRIVATE-KEY-VALUE",
    ).filterNot { line -> skip.any { line.startsWith(it) } }.map { "skeleton.projects.$project.config.payment-appstore.$it" }.toTypedArray()

    @Test
    fun `a protected environment does not start when a project switched the store on with a value missing - naming the project`() {
        runner.withPropertyValues(*store("a"), *store("b", "key-id")).run { ctx ->
            val guard = ctx.getBean("appStoreProjectGuard", DeployGuard::class.java)
            assertThat(guard.problems(prod)).singleElement().asString().startsWith("project 'b':").contains("key-id")
            assertThat(guard.problems(local)).isEmpty()
        }
    }

    @Test
    fun `a project that did not switch the store on is not judged, and a complete one passes`() {
        runner.withPropertyValues(*store("a"), "skeleton.projects.b.config.payment-appstore.bundle-id=only-this").run { ctx ->
            assertThat(ctx.getBean("appStoreProjectGuard", DeployGuard::class.java).problems(prod)).isEmpty()
        }
    }
}
