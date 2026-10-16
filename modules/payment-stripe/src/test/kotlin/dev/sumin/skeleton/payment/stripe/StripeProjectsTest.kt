package dev.sumin.skeleton.payment.stripe

import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentConfirmGateway
import dev.sumin.skeleton.payment.PaymentProviderNotFoundException
import dev.sumin.skeleton.payment.PaymentProviderRouter
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 Stripe: 프로젝트마다 자기 키를 든 클라이언트가 따로 있고, 켜지 않은 프로젝트에는 없다 */
class StripeProjectsTest {
    @Test
    fun `each project that switched Stripe on has its own client, the others have none`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, StripePaymentAutoConfiguration::class.java))
            .withPropertyValues(
                "skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb", "skeleton.projects.c.hosts=c.test", "skeleton.projects.c.schema=pc",
                "skeleton.projects.a.config.payment-stripe.enabled=true", "skeleton.projects.a.config.payment-stripe.secret-key=sk_test_a",
                "skeleton.projects.b.config.payment-stripe.enabled=true", "skeleton.projects.b.config.payment-stripe.secret-key=sk_test_b",
            )
            .run { ctx ->
                val router = ctx.getBean(PaymentProviderRouter::class.java)
                val (a, b, c) = ctx.getBean(ProjectRegistry::class.java).projects
                assertNotSame(ProjectContext.runIn(a) { router.gateway("stripe", PaymentConfirmGateway::class) }, ProjectContext.runIn(b) { router.gateway("stripe", PaymentConfirmGateway::class) })
                assertFailsWith<PaymentProviderNotFoundException> { ProjectContext.runIn(c) { router.gateway("stripe", PaymentConfirmGateway::class) } }
            }
    }
}
